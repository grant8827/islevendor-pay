package com.islevendor.pay.payment;

import com.islevendor.pay.audit.AuditLog;
import com.islevendor.pay.config.PayProperties;
import com.islevendor.pay.gateway.GatewayConfiguration;
import com.islevendor.pay.gateway.GatewayConfigurationRepository;
import com.islevendor.pay.gateway.GatewayEnvironment;
import com.islevendor.pay.web.ApiException;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 1: records payment intents only. Nothing is sent to WiPay and no
 * money moves; that is Phase 2, behind the same switch.
 */
@Service
public class PaymentIntentService {

    public record Result(PaymentIntent intent, boolean created) {
    }

    private final PayProperties props;
    private final PaymentIntentRepository intents;
    private final GatewayConfigurationRepository gateways;
    private final AuditLog audit;
    private final Clock clock;

    public PaymentIntentService(PayProperties props, PaymentIntentRepository intents,
            GatewayConfigurationRepository gateways, AuditLog audit, Clock clock) {
        this.props = props;
        this.intents = intents;
        this.gateways = gateways;
        this.audit = audit;
        this.clock = clock;
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
        if (gateway.getEnvironment() == GatewayEnvironment.LIVE && !props.wipay().liveAuthorized()) {
            throw new ApiException(HttpStatus.CONFLICT, "Live payments are not authorised");
        }

        var intent = new PaymentIntent(req.idempotencyKey(), gateway.getCode(), req.customerUserId(),
                req.orderIds(), req.amount(), req.currency(), clock.instant());
        intents.saveAndFlush(intent);
        audit.record(AuditLog.ActorType.SERVICE, null, "payment_intent.created", "payment_intent",
                intent.getId().toString(),
                Map.of("gateway", gateway.getCode(),
                        "environment", gateway.getEnvironment().name(),
                        "amount", req.amount().toPlainString(),
                        "currency", req.currency(),
                        "customerUserId", req.customerUserId().toString(),
                        "orderCount", req.orderIds().size()));
        return new Result(intent, true);
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

    private static PaymentIntent sameRequestOrConflict(PaymentIntent existing, CreatePaymentIntentRequest req) {
        boolean same = existing.getGatewayCode().equals(req.gateway())
                && existing.getCustomerUserId().equals(req.customerUserId())
                && existing.getAmount().compareTo(req.amount()) == 0
                && existing.getCurrency().equals(req.currency())
                && existing.getOrderIds().equals(req.orderIds());
        if (!same) {
            throw new ApiException(HttpStatus.CONFLICT, "This idempotency key was already used for a different payment");
        }
        return existing;
    }
}
