package com.islevendor.pay.payment;

import com.islevendor.pay.config.PayProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tells the marketplace (Node) when a payment succeeds or fails, so it can
 * credit the orders and update what the customer sees. Delivery is
 * at-least-once: straight after the change, then every minute until the
 * marketplace acknowledges. The marketplace handles repeats idempotently.
 *
 * The marketplace's answer to a success is PAID, or DUPLICATE when every
 * order had already been paid by another attempt (a refund is then needed).
 */
@Component
public class MarketplaceSync {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceSync.class);
    static final String SERVICE_TOKEN_HEADER = "X-IsleVendor-Service-Token";

    private final PayProperties props;
    private final PaymentIntentRepository intents;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final ObjectMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public MarketplaceSync(PayProperties props, PaymentIntentRepository intents, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.props = props;
        this.intents = intents;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${islevendor.pay.marketplace-sync-interval-ms:60000}",
            initialDelayString = "${islevendor.pay.marketplace-sync-interval-ms:60000}")
    public void syncOutstanding() {
        if (!props.enabled()) {
            return;
        }
        for (UUID id : intents.findIdsAwaitingMarketplaceSync()) {
            trySync(id);
        }
    }

    /** Never throws: a failure is logged and retried by {@link #syncOutstanding}. */
    public boolean trySync(UUID intentId) {
        try {
            return sync(intentId);
        } catch (RuntimeException e) {
            log.warn("Couldn't tell the marketplace about payment intent {}: {}", intentId, e.getMessage());
            return false;
        }
    }

    private boolean sync(UUID intentId) {
        PaymentIntent intent = intents.findById(intentId).orElseThrow();
        PaymentIntent.Status sent = intent.getStatus();
        if ((sent != PaymentIntent.Status.PAID && sent != PaymentIntent.Status.FAILED)
                || sent == intent.getMarketplaceSyncedStatus() || intent.getMarketplacePaymentId() == null) {
            return true;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentIntentId", intent.getId().toString());
        body.put("status", sent.name());
        body.put("amount", intent.getAmount().toPlainString());
        body.put("transactionId", intent.getGatewayTransactionId());
        body.put("message", intent.getMessage());

        HttpResponse<String> res;
        try {
            res = http.send(HttpRequest.newBuilder(URI.create(props.marketplaceInternalUrl()
                                    + "/internal/pay/payments/" + intent.getMarketplacePaymentId() + "/outcome"))
                            .timeout(Duration.ofSeconds(15))
                            .header("Content-Type", "application/json")
                            .header(SERVICE_TOKEN_HEADER, props.serviceToken())
                            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("marketplace unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("marketplace answered " + res.statusCode());
        }

        JsonNode answer = json.readTree(res.body());
        PaymentIntent.Status acknowledged;
        try {
            acknowledged = PaymentIntent.Status.valueOf(answer.path("status").asString(""));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("marketplace gave no status");
        }
        boolean consistent = sent == PaymentIntent.Status.PAID
                ? acknowledged == PaymentIntent.Status.PAID || acknowledged == PaymentIntent.Status.DUPLICATE
                : acknowledged == PaymentIntent.Status.FAILED;
        if (!consistent) {
            throw new IllegalStateException("marketplace answered " + acknowledged + " to " + sent);
        }

        tx.executeWithoutResult(status -> intents.lockById(intentId).orElseThrow()
                .marketplaceAcknowledged(acknowledged, clock.instant()));
        return true;
    }
}
