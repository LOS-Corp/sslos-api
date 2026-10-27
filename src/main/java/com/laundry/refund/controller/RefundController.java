package com.laundry.refund.controller;

import com.laundry.refund.dto.request.CreateRefundRequest;
import com.laundry.refund.dto.request.RefundDecisionRequest;
import com.laundry.refund.dto.response.RefundResponse;
import com.laundry.refund.service.RefundService;
import com.laundry.shared.security.CurrentUser;
import com.laundry.shared.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Exception refunds: Staff/Owner raise with evidence, an Owner who did not raise it decides (BR-14). */
@RestController
@RequestMapping("/api/refunds")
@RequiredArgsConstructor
@Tag(name = "Refund", description = "Exception refund requests")
public class RefundController {

    private final RefundService refundService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.OPERATOR)
    @Operation(summary = "Raise an exception refund request (Staff of the branch or Owner)")
    public RefundResponse raise(@Valid @RequestBody CreateRefundRequest request, CurrentUser requester) {
        return refundService.raise(request, requester);
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.OPERATOR)
    @Operation(summary = "Get a refund request")
    public RefundResponse get(@PathVariable UUID id, CurrentUser viewer) {
        return refundService.get(id, viewer);
    }

    @GetMapping("/pending")
    @PreAuthorize(Roles.OPERATOR)
    @Operation(summary = "Pending refund requests visible to the caller, optionally for one branch")
    public List<RefundResponse> pending(@RequestParam(required = false) UUID branchId, CurrentUser viewer) {
        return refundService.pending(branchId, viewer);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(Roles.OWNER)
    @Operation(summary = "Approve an exception refund (Owner, not the requester)")
    public RefundResponse approve(@PathVariable UUID id,
                                  @Valid @RequestBody(required = false) RefundDecisionRequest decision,
                                  CurrentUser owner) {
        return refundService.approve(id, decision == null ? null : decision.reason(), owner);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(Roles.OWNER)
    @Operation(summary = "Reject an exception refund with a reason (Owner, not the requester)")
    public RefundResponse reject(@PathVariable UUID id, @Valid @RequestBody RefundDecisionRequest decision,
                                 CurrentUser owner) {
        return refundService.reject(id, decision.reason(), owner);
    }
}
