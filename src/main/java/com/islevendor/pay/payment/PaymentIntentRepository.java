package com.islevendor.pay.payment;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, UUID> {

    Optional<PaymentIntent> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentIntent> findByReference(String reference);

    Optional<PaymentIntent> findByGatewayTransactionId(String gatewayTransactionId);

    /** One status change at a time per intent (SELECT ... FOR UPDATE). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentIntent p where p.id = :id")
    Optional<PaymentIntent> lockById(UUID id);

    /** Outcomes the marketplace hasn't acknowledged yet. */
    @Query("""
            select p.id from PaymentIntent p
            where p.status in (com.islevendor.pay.payment.PaymentIntent.Status.PAID,
                               com.islevendor.pay.payment.PaymentIntent.Status.FAILED)
              and p.marketplacePaymentId is not null
              and (p.marketplaceSyncedStatus is null or p.marketplaceSyncedStatus <> p.status)
            order by p.updatedAt
            """)
    List<UUID> findIdsAwaitingMarketplaceSync();
}
