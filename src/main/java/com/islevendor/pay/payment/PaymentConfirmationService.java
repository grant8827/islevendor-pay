package com.islevendor.pay.payment;

import com.islevendor.pay.audit.AuditLog;
import com.islevendor.pay.config.PayProperties;
import com.islevendor.pay.gateway.WiPayCheckoutClient;
import com.islevendor.pay.web.ApiException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deciding that a payment succeeded or failed. Only two things can mark an
 * intent PAID, and both are checked against the total we asked for:
 * <ul>
 *   <li>the customer's return, carrying WiPay's hash md5(transaction + total + API key);</li>
 *   <li>a signed WiPay webhook (HMAC-SHA256 over "timestamp.body", within 5 minutes),
 *       handled once per event id.</li>
 * </ul>
 * The marketplace is then told (see {@link MarketplaceSync}).
 */
@Service
public class PaymentConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfirmationService.class);
    private static final long WEBHOOK_TOLERANCE_SECONDS = 300;

    private final PayProperties props;
    private final PaymentIntentRepository intents;
    private final WiPayCheckoutClient wipay;
    private final AuditLog audit;
    private final JdbcTemplate jdbc;
    private final MarketplaceSync marketplace;
    private final Clock clock;
    private final ObjectMapper json = JsonMapper.builder().build();

    public PaymentConfirmationService(PayProperties props, PaymentIntentRepository intents, WiPayCheckoutClient wipay,
            AuditLog audit, JdbcTemplate jdbc, MarketplaceSync marketplace, Clock clock) {
        this.props = props;
        this.intents = intents;
        this.wipay = wipay;
        this.audit = audit;
        this.jdbc = jdbc;
        this.marketplace = marketplace;
        this.clock = clock;
    }

    /**
     * The customer's return from WiPay. Returns the intent it was for (empty
     * if the reference is unknown) so the caller can send the customer to
     * its status page. A success that fails verification changes nothing.
     */
    @Transactional
    public Optional<PaymentIntent> handleReturn(Map<String, String> query) {
        String reference = query.getOrDefault("order_id", "");
        if (!reference.matches("[A-Z0-9]{1,16}")) {
            return Optional.empty();
        }
        Optional<PaymentIntent> found = intents.findByReference(reference);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PaymentIntent intent = intents.lockById(found.get().getId()).orElseThrow();
        String transactionId = query.getOrDefault("transaction_id", "");

        if (!"success".equals(query.get("status"))) {
            if (!intent.isSettled()) {
                String message = query.getOrDefault("message", "");
                intent.failed(message.isBlank() ? "The card payment didn't go through" : message, clock.instant());
                audit.record(AuditLog.ActorType.SYSTEM, null, "payment_intent.failed", "payment_intent",
                        intent.getId().toString(), Map.of("source", "return"));
                syncAfterCommit(intent.getId());
            }
            return Optional.of(intent);
        }

        boolean verified = !transactionId.isEmpty() && transactionId.length() <= 100
                && (intent.getGatewayTransactionId() == null || intent.getGatewayTransactionId().equals(transactionId))
                && wipay.returnHashMatches(transactionId, intent.getAmount(), query.get("hash"));
        if (!verified) {
            log.warn("Rejected an unverifiable WiPay success return for payment intent {}", intent.getId());
            audit.record(AuditLog.ActorType.SYSTEM, null, "payment_intent.return_rejected", "payment_intent",
                    intent.getId().toString(), Map.of("reason", "hash verification failed"));
            return Optional.of(intent);
        }
        confirm(intent, transactionId, "return");
        return Optional.of(intent);
    }

    public enum WebhookOutcome { APPLIED, DUPLICATE, IGNORED, UNMATCHED, AMOUNT_MISMATCH, ALREADY_SETTLED }

    /** A WiPay Payments webhook. Throws 400 for a bad signature, 503 when not configured. */
    @Transactional
    public WebhookOutcome handleWebhook(byte[] rawBody, String signatureHeader, String timestampHeader) {
        String secret = props.wipay().paymentsWebhookSecret();
        if (secret.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WiPay payment notifications are not configured");
        }
        verifySignature(secret, rawBody, signatureHeader, timestampHeader);

        JsonNode envelope;
        try {
            envelope = json.readTree(rawBody);
        } catch (tools.jackson.core.JacksonException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid notification");
        }
        String eventId = envelope.path("id").asString("");
        if (eventId.isEmpty() || eventId.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid notification");
        }

        // Claim the event id first: a redelivery finds it taken and does nothing.
        // If anything below fails, the whole transaction (claim included) rolls
        // back and WiPay's retry is processed afresh.
        int claimed = jdbc.update("""
                insert into payments.webhook_events (gateway_code, event_id, signature_valid, outcome, payload)
                values ('WIPAY', ?, true, 'PROCESSING', ?::jsonb)
                on conflict (gateway_code, event_id) do nothing
                """, eventId, new String(rawBody, StandardCharsets.UTF_8));
        if (claimed == 0) {
            return WebhookOutcome.DUPLICATE;
        }

        WebhookOutcome outcome = applyWebhook(envelope);
        jdbc.update("update payments.webhook_events set outcome = ? where gateway_code = 'WIPAY' and event_id = ?",
                outcome.name(), eventId);
        return outcome;
    }

    private WebhookOutcome applyWebhook(JsonNode envelope) {
        if (!"payment.success".equals(envelope.path("event").asString(""))) {
            return WebhookOutcome.IGNORED;
        }
        JsonNode data = envelope.path("data");
        String transactionId = data.path("transaction_id").asString("");
        String reference = data.path("order_id").asString("");
        if (transactionId.isEmpty() || transactionId.length() > 100) {
            return WebhookOutcome.UNMATCHED;
        }
        Optional<PaymentIntent> found = intents.findByGatewayTransactionId(transactionId)
                .or(() -> reference.isEmpty() ? Optional.empty() : intents.findByReference(reference));
        if (found.isEmpty()) {
            return WebhookOutcome.UNMATCHED;
        }
        PaymentIntent intent = intents.lockById(found.get().getId()).orElseThrow();
        if (intent.getGatewayTransactionId() != null && !intent.getGatewayTransactionId().equals(transactionId)) {
            return WebhookOutcome.UNMATCHED;
        }
        // The amount must be stated and must be what we asked for.
        BigDecimal total;
        try {
            total = new BigDecimal(data.path("total").asString(""));
        } catch (NumberFormatException e) {
            total = null;
        }
        if (total == null || total.compareTo(intent.getAmount()) != 0) {
            log.warn("WiPay payment.success for intent {} has total {}, expected {}: not applied",
                    intent.getId(), data.path("total"), intent.getAmount());
            return WebhookOutcome.AMOUNT_MISMATCH;
        }
        if (intent.isSettled()) {
            return WebhookOutcome.ALREADY_SETTLED;
        }
        confirm(intent, transactionId, "webhook");
        return WebhookOutcome.APPLIED;
    }

    /** Marks a verified payment PAID, once. The caller holds the row lock. */
    private void confirm(PaymentIntent intent, String transactionId, String source) {
        if (intent.isSettled()) {
            return;
        }
        intent.paid(transactionId, clock.instant());
        audit.record(AuditLog.ActorType.SYSTEM, null, "payment_intent.paid", "payment_intent",
                intent.getId().toString(),
                Map.of("source", source, "transactionId", transactionId, "amount", intent.getAmount().toPlainString()));
        syncAfterCommit(intent.getId());
    }

    /** Tell the marketplace straight away once committed; the minute-by-minute retry covers failures. */
    private void syncAfterCommit(UUID intentId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                marketplace.trySync(intentId);
            }
        });
    }

    private void verifySignature(String secret, byte[] rawBody, String signatureHeader, String timestampHeader) {
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader == null ? "" : timestampHeader.trim());
        } catch (NumberFormatException e) {
            throw invalidSignature();
        }
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")
                || Math.abs(clock.instant().getEpochSecond() - timestamp) > WEBHOOK_TOLERANCE_SECONDS) {
            throw invalidSignature();
        }
        byte[] expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            expected = HexFormat.of().formatHex(mac.doFinal(rawBody)).getBytes(StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        byte[] provided = signatureHeader.substring("sha256=".length()).toLowerCase(Locale.ROOT)
                .getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, provided)) {
            throw invalidSignature();
        }
    }

    private static ApiException invalidSignature() {
        return new ApiException(HttpStatus.BAD_REQUEST, "Invalid or expired WiPay signature");
    }
}
