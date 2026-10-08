package com.laundry.refund.repository;

import com.laundry.refund.entity.RefundRequest;
import com.laundry.refund.entity.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Refund Request repository
 */
@Repository
public interface RefundRequestRepository extends JpaRepository<RefundRequest, UUID> {

    /**
     * Find by status
     */
    List<RefundRequest> findByStatus(RefundStatus status);

    /**
     * Find by branch ID
     */
    List<RefundRequest> findByBranchId(UUID branchId);

    /**
     * Find by branch and status
     */
    List<RefundRequest> findByBranchIdAndStatus(UUID branchId, RefundStatus status);

    /**
     * Find pending approval by branch
     */
    @Query("SELECT r FROM RefundRequest r WHERE r.branchId = :branchId " +
           "AND r.status IN ('CREATED', 'PENDING_APPROVAL')")
    List<RefundRequest> findPendingByBranch(@Param("branchId") UUID branchId);

    /**
     * Find by payment ID
     */
    List<RefundRequest> findByPaymentId(UUID paymentId);

    /**
     * Find by requester
     */
    List<RefundRequest> findByRequestedBy(UUID requestedBy);

    /**
     * Find by approved by
     */
    List<RefundRequest> findByApprovedBy(UUID approvedBy);

    /**
     * Count pending by branch
     */
    @Query("SELECT COUNT(r) FROM RefundRequest r WHERE r.branchId = :branchId " +
           "AND r.status IN ('CREATED', 'PENDING_APPROVAL')")
    long countPendingByBranch(@Param("branchId") UUID branchId);
}
