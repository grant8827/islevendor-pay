package com.islevendor.pay.payment;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Called by the Node backend only (service token); never by browsers. */
@RestController
@RequestMapping("/internal/v1/payment-intents")
public class InternalPaymentIntentController {

    public record PaymentIntentView(
            UUID id,
            UUID marketplacePaymentId,
            String reference,
            PaymentIntent.Status status,
            String gateway,
            UUID customerUserId,
            Set<UUID> orderIds,
            BigDecimal amount,
            String currency,
            String checkoutUrl,
            String gatewayTransactionId,
            String message,
            Instant createdAt,
            Instant paidAt) {

        static PaymentIntentView of(PaymentIntent i) {
            return new PaymentIntentView(i.getId(), i.getMarketplacePaymentId(), i.getReference(), i.getStatus(),
                    i.getGatewayCode(), i.getCustomerUserId(), i.getOrderIds(), i.getAmount(), i.getCurrency(),
                    i.getStatus() == PaymentIntent.Status.PENDING ? i.getCheckoutUrl() : null,
                    i.getGatewayTransactionId(), i.getMessage(), i.getCreatedAt(), i.getPaidAt());
        }
    }

    private final PaymentIntentService service;

    public InternalPaymentIntentController(PaymentIntentService service) {
        this.service = service;
    }

    /**
     * Creates the intent and opens WiPay's checkout for it. 201 with
     * status PENDING and a checkoutUrl to send the customer to, or FAILED
     * with a message. A repeat with the same idempotency key returns the
     * original (200) without asking WiPay again.
     */
    @PostMapping
    public ResponseEntity<PaymentIntentView> create(@Valid @RequestBody CreatePaymentIntentRequest req) {
        PaymentIntentService.Result result;
        try {
            result = service.create(req);
        } catch (DataIntegrityViolationException e) {
            // Two identical calls at once: the other one inserted first.
            result = service.replay(req);
        }
        if (!result.created()) {
            return ResponseEntity.ok(PaymentIntentView.of(result.intent()));
        }
        PaymentIntent opened = service.openCheckout(result.intent().getId(), req.customer(), req.feeStructure());
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentIntentView.of(opened));
    }

    @GetMapping("/{id}")
    public PaymentIntentView get(@PathVariable UUID id) {
        return PaymentIntentView.of(service.get(id));
    }
}
