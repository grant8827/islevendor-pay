package com.islevendor.pay.payment;

import com.islevendor.pay.audit.AuditLog;
import com.islevendor.pay.config.PayProperties;
import com.islevendor.pay.gateway.GatewayConfiguration;
import com.islevendor.pay.gateway.GatewayConfigurationRepository;
import com.islevendor.pay.gateway.GatewayEnvironment;
import com.islevendor.pay.gateway.WiPayCheckoutClient;
import com.islevendor.pay.web.ApiException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creating payment intents and opening WiPay's hosted checkout for them.
 * Confirming payment is {@link PaymentConfirmationService}.
 */
@Service
public class PaymentIntentService {

    public record Result(PaymentIntent intent, boolean created) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PayProperties props;
    private final PaymentIntentRepository intents;
    private final GatewayConfigurationRepository gateways;
    private final WiPayCheckoutClient wipay;
    private final AuditLog audit;
    private final Clock clock;
    private final TransactionTemplate tx;

    public PaymentIntentService(PayProperties props, PaymentIntentRepository intents,
            GatewayConfigurationRepository gateways, WiPayCheckoutClient wipay, AuditLog audit, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.props = props;
        this.intents = intents;
        this.gateways = gateways;
        this.wipay = wipay;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public void requireEnabled() {
        if (!props.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "IsleVendor Pay is not enabled");
        }
    }

    @Transactional
    public Result create(CreatePaymentIntentRequest req) {
        requireEnabled();
        var existing = intents.findByIdempotencyKey(req.idempotencyKey());
        if (existing.isPresent()) {
            return new Result(sameRequestOrConflict(existing.get(), req), false);
        }

        GatewayConfiguration gateway = gateways.findById(req.gateway())
                .filter(GatewayConfiguration::isEnabled)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "That payment gateway is not enabled"));
        // The gateway row and the deployment must agree: a dashboard switch alone can't reach live.
        if (gateway.getEnvironment() != wipay.environment()) {
            throw new ApiException(HttpStatus.CONFLICT, "The gateway's environment doesn't match this deployment");
        }
        if (gateway.getEnvironment() == GatewayEnvironment.LIVE && !props.wipay().liveAuthorized()) {
            throw new ApiException(HttpStatus.CONFLICT, "Live payments are not authorised");
        }

        var intent = new PaymentIntent(req.idempotencyKey(), newReference(), req.marketplacePaymentId(),
                gateway.getCode(), req.customerUserId(), req.orderIds(), req.amount(), req.currency(), clock.instant());
        intents.saveAndFlush(intent);
        audit.record(AuditLog.ActorType.SERVICE, null, "payment_intent.created", "payment_intent",
                intent.getId().toString(),
                Map.of("gateway", gateway.getCode(),
                        "environment", gateway.getEnvironment().name(),
                        "reference", intent.getReference(),
                        "amount", req.amount().toPlainString(),
                        "currency", req.currency(),
                        "customerUserId", req.customerUserId().toString(),
                        "orderCount", req.orderIds().size()));
        return new Result(intent, true);
    }

    /**
     * Asks WiPay for a checkout page for a newly created intent. Runs outside
     * any transaction (no database lock is held during the call). If WiPay
     * can't be reached the intent is FAILED with the reason and the customer
     * can try again: nothing was charged.
     */
    public PaymentIntent openCheckout(UUID intentId, CreatePaymentIntentRequest.Customer customer, String feeStructure) {
        PaymentIntent intent = intents.findById(intentId).orElseThrow();
        WiPayCheckoutClient.CheckoutPage page = null;
        String failure = null;
        try {
            page = wipay.openCheckout(new WiPayCheckoutClient.CheckoutRequest(intent.getReference(), intent.getId(),
                    intent.getAmount(), feeStructure, customer.email(), customer.name(), customer.phone()));
        } catch (WiPayCheckoutClient.CheckoutUnavailableException e) {
            failure = e.getMessage();
        }

        final var opened = page;
        final String reason = failure;
        return tx.execute(status -> {
            PaymentIntent locked = intents.lockById(intentId).orElseThrow();
            if (opened != null) {
                locked.checkoutOpened(opened.url(), opened.transactionId(), clock.instant());
                audit.record(AuditLog.ActorType.SERVICE, null, "payment_intent.checkout_opened", "payment_intent",
                        intentId.toString(), Map.of("environment", wipay.environment().name()));
            } else {
                locked.failed("Couldn't start the card payment: " + reason, clock.instant());
                audit.record(AuditLog.ActorType.SERVICE, null, "payment_intent.checkout_failed", "payment_intent",
                        intentId.toString(), Map.of("reason", reason));
            }
            return locked;
        });
    }

    /** After losing an insert race on the idempotency key: the winner's intent, if it is the same request. */
    @Transactional(readOnly = true)
    public Result replay(CreatePaymentIntentRequest req) {
        PaymentIntent existing = intents.findByIdempotencyKey(req.idempotencyKey())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "Please retry"));
        return new Result(sameRequestOrConflict(existing, req), false);
    }

    @Transactional(readOnly = true)
    public PaymentIntent get(UUID id) {
        requireEnabled();
        return intents.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Not found"));
    }

    /** WiPay's order_id: capitals and digits, 16 at most. */
    static String newReference() {
        byte[] random = new byte[3];
        RANDOM.nextBytes(random);
        String ref = "IP" + Long.toString(System.currentTimeMillis(), 36).toUpperCase(Locale.ROOT)
                + HexFormat.of().withUpperCase().formatHex(random);
        return ref.length() > 16 ? ref.substring(0, 16) : ref;
    }

    private static PaymentIntent sameRequestOrConflict(PaymentIntent existing, CreatePaymentIntentRequest req) {
        boolean same = existing.getGatewayCode().equals(req.gateway())
                && existing.getCustomerUserId().equals(req.customerUserId())
                && req.marketplacePaymentId().equals(existing.getMarketplacePaymentId())
                && existing.getAmount().compareTo(req.amount()) == 0
                && existing.getCurrency().equals(req.currency())
                && existing.getOrderIds().equals(req.orderIds());
        if (!same) {
            throw new ApiException(HttpStatus.CONFLICT, "This idempotency key was already used for a different payment");
        }
        return existing;
    }
}
