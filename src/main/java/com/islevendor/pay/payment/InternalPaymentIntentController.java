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
            PaymentIntent.Status status,
            String gateway,
            UUID customerUserId,
            Set<UUID> orderIds,
            BigDecimal amount,
            String currency,
            Instant createdAt) {

        static PaymentIntentView of(PaymentIntent i) {
            return new PaymentIntentView(i.getId(), i.getStatus(), i.getGatewayCode(), i.getCustomerUserId(),
                    i.getOrderIds(), i.getAmount(), i.getCurrency(), i.getCreatedAt());
        }
    }

    private final PaymentIntentService service;

    public InternalPaymentIntentController(PaymentIntentService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PaymentIntentView> create(@Valid @RequestBody CreatePaymentIntentRequest req) {
        PaymentIntentService.Result result;
        try {
            result = service.create(req);
        } catch (DataIntegrityViolationException e) {
            // Two identical calls at once: the other one inserted first.
            result = service.replay(req);
        }
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(PaymentIntentView.of(result.intent()));
    }

    @GetMapping("/{id}")
    public PaymentIntentView get(@PathVariable UUID id) {
        return PaymentIntentView.of(service.get(id));
    }
}
