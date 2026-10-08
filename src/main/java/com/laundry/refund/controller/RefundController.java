package com.laundry.refund.controller;

import com.laundry.refund.dto.request.ApproveRefundRequest;
import com.laundry.refund.dto.request.CreateRefundRequest;
import com.laundry.refund.dto.response.RefundResponse;
import com.laundry.refund.service.RefundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Refund API Controller
 * SRS: FR-PAY-05 - Staff tạo Refund Request
 * SRS: FR-PAY-06 - Manager của đúng branch Approve/Reject
 */
@RestController
@RequestMapping("/api/refunds")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Refund", description = "Refund request management API")
public class RefundController {

    private final RefundService refundService;

    /**
     * FR-PAY-05: Create a refund request (Staff only)
     */
    @PostMapping
    @PreAuthorize("hasRole('STAFF')")
    @Operation(summary = "Create refund request", description = "Staff creates a refund request")
    public ResponseEntity<RefundResponse> createRefundRequest(
            @Valid @RequestBody CreateRefundRequest request,
            @RequestHeader(value = "X-User-Id", required = false) UUID staffId) {
        log.info("Create refund request: paymentId={}, staffId={}", request.getPaymentId(), staffId);
        RefundResponse response = refundService.createRefundRequest(request, staffId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get refund request by ID
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('STAFF', 'MANAGER', 'OWNER', 'ADMIN')")
    @Operation(summary = "Get refund request", description = "Get refund request by ID")
    public ResponseEntity<RefundResponse> getRefundRequest(@PathVariable UUID id) {
        RefundResponse response = refundService.getRefundRequest(id);
        return ResponseEntity.ok(response);
    }

    /**
     * FR-PAY-06: Get pending refund requests for manager's branch
     */
    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('MANAGER', 'OWNER', 'ADMIN')")
    @Operation(summary = "Get pending refunds", description = "Get pending refund requests for a branch")
    public ResponseEntity<List<RefundResponse>> getPendingRefunds(
            @RequestParam UUID branchId) {
        List<RefundResponse> responses = refundService.getPendingRefundsByBranch(branchId);
        return ResponseEntity.ok(responses);
    }

    /**
     * FR-PAY-06: Approve refund request (Manager only)
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('MANAGER')")
    @Operation(summary = "Approve refund request", description = "Manager approves a refund request")
    public ResponseEntity<RefundResponse> approveRefundRequest(
            @PathVariable UUID id,
            @Valid @RequestBody ApproveRefundRequest request) {
        log.info("Approve refund request: id={}, managerId={}", id, request.getManagerId());
        RefundResponse response = refundService.approveRefundRequest(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * FR-PAY-06: Reject refund request (Manager only)
     */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('MANAGER')")
    @Operation(summary = "Reject refund request", description = "Manager rejects a refund request")
    public ResponseEntity<RefundResponse> rejectRefundRequest(
            @PathVariable UUID id,
            @Valid @RequestBody ApproveRefundRequest request,
            @RequestParam String rejectionReason) {
        log.info("Reject refund request: id={}, managerId={}", id, request.getManagerId());
        RefundResponse response = refundService.rejectRefundRequest(id, request, rejectionReason);
        return ResponseEntity.ok(response);
    }
}
