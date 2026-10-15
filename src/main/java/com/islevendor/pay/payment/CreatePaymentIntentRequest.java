package com.islevendor.pay.payment;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/** Sent by the Node backend. The idempotency key makes a retried call safe. */
public record CreatePaymentIntentRequest(
        @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{8,100}") String idempotencyKey,
        @NotNull @Pattern(regexp = "[A-Z][A-Z_]{1,31}") String gateway,
        @NotNull UUID customerUserId,
        @NotEmpty @Size(max = 50) Set<@NotNull UUID> orderIds,
        @NotNull @DecimalMin("0.01") @DecimalMax("10000000.00") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotNull @Pattern(regexp = "JMD") String currency) {
}
