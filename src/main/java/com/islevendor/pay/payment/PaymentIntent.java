package com.islevendor.pay.payment;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A request to collect money for one or more marketplace orders. The orders
 * and the customer live in the marketplace's tables and are referenced by id.
 */
@Entity
@Table(name = "payment_intents")
public class PaymentIntent {

    public enum Status { CREATED, PENDING, PAID, FAILED, CANCELLED }

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "gateway_code", nullable = false)
    private String gatewayCode;

    @Column(name = "customer_user_id", nullable = false)
    private UUID customerUserId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "payment_intent_orders", joinColumns = @JoinColumn(name = "payment_intent_id"))
    @Column(name = "marketplace_order_id", nullable = false)
    private Set<UUID> orderIds = new LinkedHashSet<>();

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "gateway_transaction_id")
    private String gatewayTransactionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentIntent() {
    }

    PaymentIntent(String idempotencyKey, String gatewayCode, UUID customerUserId, Set<UUID> orderIds,
            BigDecimal amount, String currency, Instant now) {
        this.id = UUID.randomUUID();
        this.idempotencyKey = idempotencyKey;
        this.gatewayCode = gatewayCode;
        this.customerUserId = customerUserId;
        this.orderIds = new LinkedHashSet<>(orderIds);
        this.amount = amount;
        this.currency = currency;
        this.status = Status.CREATED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getGatewayCode() {
        return gatewayCode;
    }

    public UUID getCustomerUserId() {
        return customerUserId;
    }

    public Set<UUID> getOrderIds() {
        return Collections.unmodifiableSet(orderIds);
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
