package com.islevendor.pay.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * Sent by the Node backend when a customer checks out. The idempotency key
 * makes a retried call safe. The customer's contact details are passed to
 * WiPay's checkout page and are not stored here.
 */
public record CreatePaymentIntentRequest(
        @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{8,100}") String idempotencyKey,
        @NotNull UUID marketplacePaymentId,
        @NotNull @Pattern(regexp = "[A-Z][A-Z_]{1,31}") String gateway,
        @NotNull UUID customerUserId,
        @NotEmpty @Size(max = 50) Set<@NotNull UUID> orderIds,
        @NotNull @DecimalMin("0.01") @DecimalMax("10000000.00") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotNull @Pattern(regexp = "JMD") String currency,
        @NotNull @Pattern(regexp = "customer_pay|merchant_absorb|split") String feeStructure,
        @NotNull @Valid Customer customer) {

    public record Customer(
            @NotBlank @Email @Size(max = 50) String email,
            @NotBlank @Size(max = 60) String name,
            @Pattern(regexp = "\\+[1-9][0-9]{6,14}") String phone) {
    }
}
