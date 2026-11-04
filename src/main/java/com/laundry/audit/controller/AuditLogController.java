package com.laundry.audit.controller;

import com.laundry.audit.service.AuditService;
import com.laundry.audit.service.AuditService.AuditLogView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/audit-logs")
@RequiredArgsConstructor
@Tag(name = "Audit log", description = "Who did what, when, before and after, and why (UC-10, NFR-14)")
public class AuditLogController {

    private final AuditService audit;

    @GetMapping
    @Operation(summary = "Audit entries, newest first; branchId narrows the list to one branch")
    public Page<AuditLogView> list(@RequestParam(required = false) UUID branchId,
                                   @RequestParam(defaultValue = "0") @Min(0) int page,
                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return audit.list(branchId, page, size);
    }
}
