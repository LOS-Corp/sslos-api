package com.laundry.user.controller;

import com.laundry.user.dto.AdminUserDtos.AdminUserDto;
import com.laundry.user.dto.AdminUserDtos.CreateUserRequest;
import com.laundry.user.dto.AdminUserDtos.ResetPasswordRequest;
import com.laundry.user.dto.AdminUserDtos.UpdateStatusRequest;
import com.laundry.user.dto.AdminUserDtos.UpdateUserRequest;
import com.laundry.user.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Operator accounts (ADMIN / OWNER / STAFF); ADMIN only, see SecurityConfig. */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {
    private final AdminUserService adminUserService;

    @GetMapping
    public List<AdminUserDto> list(@RequestParam(required = false) String role,
                                   @RequestParam(required = false) String q) {
        return adminUserService.list(role, q);
    }

    @PostMapping
    public AdminUserDto create(@Valid @RequestBody CreateUserRequest request) {
        return adminUserService.create(request);
    }

    @PutMapping("/{id}")
    public AdminUserDto update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return adminUserService.update(id, request);
    }

    @PutMapping("/{id}/status")
    public AdminUserDto setStatus(@PathVariable UUID id, @Valid @RequestBody UpdateStatusRequest request) {
        return adminUserService.setStatus(id, request.status());
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<Void> resetPassword(@PathVariable UUID id, @Valid @RequestBody ResetPasswordRequest request) {
        adminUserService.resetPassword(id, request.password());
        return ResponseEntity.noContent().build();
    }
}
