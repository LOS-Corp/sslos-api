package com.laundry.refund.service;

import com.laundry.payment.entity.Payment;
import com.laundry.payment.entity.PaymentStatus;
import com.laundry.payment.repository.PaymentRepository;
import com.laundry.refund.dto.request.ApproveRefundRequest;
import com.laundry.refund.dto.request.CreateRefundRequest;
import com.laundry.refund.dto.response.RefundResponse;
import com.laundry.refund.entity.RefundRequest;
import com.laundry.refund.entity.RefundStatus;
import com.laundry.refund.exception.RefundException;
import com.laundry.refund.repository.RefundRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Refund Service
 * SRS: FR-PAY-05 - Staff tạo Refund Request
 * SRS: FR-PAY-06 - Separation of duty (Manager duyệt, Owner override)
 * SRS: FR-PAY-07 - Branch fund ownership
 * SRS: FR-PAY-08 - Refund status tracking
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundService {

    private final RefundRequestRepository refundRequestRepository;
    private final PaymentRepository paymentRepository;

    /**
     * FR-PAY-05: Staff creates a refund request
     * BR-RFD-001: Chỉ Staff tạo Refund Request
     */
    @Transactional
    public RefundResponse createRefundRequest(CreateRefundRequest request, UUID staffId) {
        log.info("Creating refund request: paymentId={}, amount={}, staffId={}",
                request.getPaymentId(), request.getAmount(), staffId);

        // Verify payment exists and is eligible for refund
        Payment payment = paymentRepository.findById(request.getPaymentId())
                .orElseThrow(() -> new RefundException("Payment not found: " + request.getPaymentId()));

        // SRS: FR-PAY-04 - Payment history: Payment phải đã paid mới được refund
        if (payment.getStatus() != PaymentStatus.PAID) {
            throw new RefundException("Only PAID payments can be refunded. Current status: " + payment.getStatus());
        }

        // Check if there's already a pending/approved refund request
        List<RefundRequest> existingRequests = refundRequestRepository.findByPaymentId(request.getPaymentId());
        boolean hasPendingRefund = existingRequests.stream()
                .anyMatch(r -> r.getStatus() == RefundStatus.PENDING_APPROVAL ||
                               r.getStatus() == RefundStatus.APPROVED ||
                               r.getStatus() == RefundStatus.PROCESSING);
        if (hasPendingRefund) {
            throw new RefundException("A refund request is already pending for this payment");
        }

        // Check refund amount does not exceed payment amount
        if (request.getAmount().compareTo(payment.getAmount()) > 0) {
            throw new RefundException("Refund amount exceeds payment amount");
        }

        // Create refund request
        RefundRequest refundRequest = RefundRequest.builder()
                .paymentId(request.getPaymentId())
                .orderId(request.getOrderId())
                .transactionId(request.getTransactionId())
                .branchId(request.getBranchId())
                .requestedBy(staffId)
                .amount(request.getAmount())
                .reason(request.getReason())
                .evidence(request.getEvidence())
                .status(RefundStatus.CREATED)
                .build();

        refundRequest = refundRequestRepository.save(refundRequest);
        log.info("Refund request created: id={}", refundRequest.getId());

        return toResponse(refundRequest);
    }

    /**
     * FR-PAY-06: Manager approves refund request
     * BR-RFD-001: Manager của đúng branch duyệt
     * BR-RFD-002: Refund luôn gắn branch để đảm bảo traceability
     */
    @Transactional
    public RefundResponse approveRefundRequest(UUID refundId, ApproveRefundRequest request) {
        log.info("Approving refund request: refundId={}, managerId={}", refundId, request.getManagerId());

        RefundRequest refundRequest = refundRequestRepository.findById(refundId)
                .orElseThrow(() -> new RefundException("Refund request not found: " + refundId));

        // BR-RFD-001: Manager phải cùng branch
        if (!refundRequest.getBranchId().equals(request.getManagerBranchId())) {
            throw new RefundException("Manager can only approve refunds for their own branch");
        }

        // BR-RFD-001: Requester không được tự approve
        if (!refundRequest.canBeApprovedBy(request.getManagerId())) {
            throw new RefundException("Invalid approval - requester cannot approve their own request or request is not pending");
        }

        // Update status to APPROVED
        refundRequest.setStatus(RefundStatus.APPROVED);
        refundRequest.setApprovedBy(request.getManagerId());
        refundRequest.setApprovedAt(LocalDateTime.now());

        refundRequest = refundRequestRepository.save(refundRequest);
        log.info("Refund request approved: id={}, managerId={}", refundId, request.getManagerId());

        return toResponse(refundRequest);
    }

    /**
     * FR-PAY-06: Manager rejects refund request
     */
    @Transactional
    public RefundResponse rejectRefundRequest(UUID refundId, ApproveRefundRequest request, String rejectionReason) {
        log.info("Rejecting refund request: refundId={}, managerId={}, reason={}",
                refundId, request.getManagerId(), rejectionReason);

        RefundRequest refundRequest = refundRequestRepository.findById(refundId)
                .orElseThrow(() -> new RefundException("Refund request not found: " + refundId));

        // BR-RFD-001: Manager phải cùng branch
        if (!refundRequest.getBranchId().equals(request.getManagerBranchId())) {
            throw new RefundException("Manager can only reject refunds for their own branch");
        }

        if (!refundRequest.canBeApprovedBy(request.getManagerId())) {
            throw new RefundException("Invalid rejection - requester cannot reject their own request or request is not pending");
        }

        refundRequest.setStatus(RefundStatus.REJECTED);
        refundRequest.setApprovedBy(request.getManagerId());
        refundRequest.setRejectionReason(rejectionReason);
        refundRequest.setApprovedAt(LocalDateTime.now());

        refundRequest = refundRequestRepository.save(refundRequest);
        log.info("Refund request rejected: id={}", refundId);

        return toResponse(refundRequest);
    }

    /**
     * Get refund request by ID
     */
    @Transactional(readOnly = true)
    public RefundResponse getRefundRequest(UUID refundId) {
        RefundRequest refundRequest = refundRequestRepository.findById(refundId)
                .orElseThrow(() -> new RefundException("Refund request not found: " + refundId));
        return toResponse(refundRequest);
    }

    /**
     * Get all pending refund requests for a branch
     * FR-PAY-06: Manager của branch xem được các request
     */
    @Transactional(readOnly = true)
    public List<RefundResponse> getPendingRefundsByBranch(UUID branchId) {
        return refundRequestRepository.findPendingByBranch(branchId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get all refund requests for a branch
     */
    @Transactional(readOnly = true)
    public List<RefundResponse> getRefundsByBranch(UUID branchId) {
        return refundRequestRepository.findByBranchId(branchId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * FR-PAY-08: Update refund status to processing
     */
    @Transactional
    public void markAsProcessing(UUID refundId) {
        RefundRequest refundRequest = refundRequestRepository.findById(refundId)
                .orElseThrow(() -> new RefundException("Refund request not found: " + refundId));

        if (refundRequest.getStatus() != RefundStatus.APPROVED) {
            throw new RefundException("Only APPROVED refunds can be processed");
        }

        refundRequest.setStatus(RefundStatus.PROCESSING);
        refundRequestRepository.save(refundRequest);
        log.info("Refund marked as processing: id={}", refundId);
    }

    /**
     * FR-PAY-08: Complete refund
     */
    @Transactional
    public void completeRefund(UUID refundId) {
        RefundRequest refundRequest = refundRequestRepository.findById(refundId)
                .orElseThrow(() -> new RefundException("Refund request not found: " + refundId));

        if (refundRequest.getStatus() != RefundStatus.PROCESSING) {
            throw new RefundException("Only PROCESSING refunds can be completed");
        }

        refundRequest.setStatus(RefundStatus.COMPLETED);
        refundRequest.setCompletedAt(LocalDateTime.now());
        refundRequestRepository.save(refundRequest);

        log.info("Refund completed: id={}, amount={}", refundId, refundRequest.getAmount());
    }

    /**
     * Convert entity to response DTO
     */
    private RefundResponse toResponse(RefundRequest refundRequest) {
        return RefundResponse.builder()
                .id(refundRequest.getId())
                .paymentId(refundRequest.getPaymentId())
                .orderId(refundRequest.getOrderId())
                .transactionId(refundRequest.getTransactionId())
                .branchId(refundRequest.getBranchId())
                .requestedBy(refundRequest.getRequestedBy())
                .approvedBy(refundRequest.getApprovedBy())
                .amount(refundRequest.getAmount())
                .reason(refundRequest.getReason())
                .evidence(refundRequest.getEvidence())
                .status(refundRequest.getStatus())
                .rejectionReason(refundRequest.getRejectionReason())
                .createdAt(refundRequest.getCreatedAt())
                .updatedAt(refundRequest.getUpdatedAt())
                .approvedAt(refundRequest.getApprovedAt())
                .completedAt(refundRequest.getCompletedAt())
                .build();
    }
}
