package com.laundry.refund.repository;

import com.laundry.refund.entity.RefundRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefundRequest r where r.id = :id")
    Optional<RefundRequest> findByIdForUpdate(@Param("id") UUID id);

    List<RefundRequest> findByPaymentId(UUID paymentId);

    @Query("select r from RefundRequest r where r.status in ('CREATED', 'PENDING_APPROVAL') order by r.createdAt")
    List<RefundRequest> findPending();

    @Query("select r from RefundRequest r where r.branchId in :branchIds "
            + "and r.status in ('CREATED', 'PENDING_APPROVAL') order by r.createdAt")
    List<RefundRequest> findPendingByBranches(@Param("branchIds") Collection<UUID> branchIds);

    /** Branch of the booking paid by a transaction (scheduled bookings carry the branch). */
    @Query(value = "select sb.branch_id from scheduled_bookings sb where sb.transaction_id = :transactionId",
            nativeQuery = true)
    Optional<UUID> findBranchOfTransaction(@Param("transactionId") UUID transactionId);
}
