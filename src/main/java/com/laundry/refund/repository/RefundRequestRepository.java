package com.laundry.refund.repository;

import com.laundry.refund.entity.RefundRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    /** What counts against the cap of a payment (BR-14), optionally leaving out one request. */
    default BigDecimal countedAgainstCap(UUID paymentId, UUID excludeRefundId) {
        return findByPaymentId(paymentId).stream()
                .filter(RefundRequest::countsTowardCap)
                .filter(r -> !r.getId().equals(excludeRefundId))
                .map(RefundRequest::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Query("select r from RefundRequest r where r.id = :id")
    Optional<RefundRequest> findByIdForUpdate(@Param("id") UUID id);

    List<RefundRequest> findByPaymentId(UUID paymentId);

    @Query("select r from RefundRequest r where r.status in ('CREATED', 'PENDING_APPROVAL') order by r.createdAt")
    List<RefundRequest> findPending();

    /** Whether a transaction paid for a booking (exception refunds are raised against bookings only). */
    @Query(value = "select exists (select 1 from scheduled_bookings sb where sb.transaction_id = :transactionId)",
            nativeQuery = true)
    boolean isBookingTransaction(@Param("transactionId") UUID transactionId);
}
