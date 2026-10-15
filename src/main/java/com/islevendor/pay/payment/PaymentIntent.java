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

    public enum Status {
        CREATED,
        // The customer has a WiPay checkout page.
        PENDING,
        PAID,
        FAILED,
        CANCELLED,
        // Paid, but the marketplace says every order was already paid by another attempt: refund needed.
        DUPLICATE
    }

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

    @Column(length = 16)
    private String reference;

    @Column(name = "marketplace_payment_id")
    private UUID marketplacePaymentId;

    @Column(name = "checkout_url", length = 2048)
    private String checkoutUrl;

    @Column(length = 500)
    private String message;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "marketplace_synced_status")
    private Status marketplaceSyncedStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentIntent() {
    }

    PaymentIntent(String idempotencyKey, String reference, UUID marketplacePaymentId, String gatewayCode,
            UUID customerUserId, Set<UUID> orderIds, BigDecimal amount, String currency, Instant now) {
        this.id = UUID.randomUUID();
        this.reference = reference;
        this.marketplacePaymentId = marketplacePaymentId;
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

    public String getGatewayTransactionId() {
        return gatewayTransactionId;
    }

    public String getReference() {
        return reference;
    }

    public UUID getMarketplacePaymentId() {
        return marketplacePaymentId;
    }

    public String getCheckoutUrl() {
        return checkoutUrl;
    }

    public String getMessage() {
        return message;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Status getMarketplaceSyncedStatus() {
        return marketplaceSyncedStatus;
    }

    /** Paid or duplicate: the money arrived and nothing may change that. */
    public boolean isSettled() {
        return status == Status.PAID || status == Status.DUPLICATE;
    }

    void checkoutOpened(String url, String transactionId, Instant now) {
        this.status = Status.PENDING;
        this.checkoutUrl = url;
        this.gatewayTransactionId = transactionId;
        this.updatedAt = now;
    }

    /** Only before payment: a verified success always wins over an earlier failure. */
    void failed(String reason, Instant now) {
        if (isSettled()) {
            return;
        }
        this.status = Status.FAILED;
        this.checkoutUrl = null;
        this.message = reason.length() > 500 ? reason.substring(0, 500) : reason;
        this.updatedAt = now;
    }

    void paid(String transactionId, Instant now) {
        this.status = Status.PAID;
        this.gatewayTransactionId = transactionId;
        this.paidAt = now;
        this.checkoutUrl = null;
        this.message = null;
        this.updatedAt = now;
    }

    /** What the marketplace answered when told the outcome. */
    void marketplaceAcknowledged(Status acknowledged, Instant now) {
        if (acknowledged == Status.DUPLICATE && status == Status.PAID) {
            this.status = Status.DUPLICATE;
            this.message = "Paid, but these orders were already paid by another attempt: refund needed";
        }
        this.marketplaceSyncedStatus = acknowledged;
        this.updatedAt = now;
    }
}
