package com.laundry.user.controller;

import com.laundry.shared.security.CurrentUser;
import com.laundry.shared.security.Role;
import com.laundry.shared.security.Roles;
import com.laundry.user.dto.AdminUserDtos.AdminUserDto;
import com.laundry.user.dto.AdminUserDtos.CreateUserRequest;
import com.laundry.user.dto.AdminUserDtos.ResetPasswordRequest;
import com.laundry.user.dto.AdminUserDtos.UpdateStatusRequest;
import com.laundry.user.dto.AdminUserDtos.UpdateUserRequest;
import com.laundry.user.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

/** Operator accounts: Admin → Admin/Owner, Owner → Staff and their branches (task/09). */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize(Roles.OWNER_OR_ADMIN)
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping
    public List<AdminUserDto> list(@RequestParam(required = false) String role,
                                   @RequestParam(required = false) String q,
                                   CurrentUser actor) {
        return adminUserService.list(role, q, actor);
    }

    @GetMapping("/manageable-roles")
    public List<Role> manageableRoles(CurrentUser actor) {
        return adminUserService.manageableRoles(actor);
    }

    @GetMapping("/assignable-branches")
    public List<UUID> assignableBranches(CurrentUser actor) {
        return adminUserService.assignableBranches(actor);
    }

    @PostMapping
    public AdminUserDto create(@Valid @RequestBody CreateUserRequest request, CurrentUser actor) {
        return adminUserService.create(request, actor);
    }

    @PutMapping("/{id}")
    public AdminUserDto update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request,
                               CurrentUser actor) {
        return adminUserService.update(id, request, actor);
    }

    @PutMapping("/{id}/status")
    public AdminUserDto setStatus(@PathVariable UUID id, @Valid @RequestBody UpdateStatusRequest request,
                                  CurrentUser actor) {
        return adminUserService.setStatus(id, request.status(), actor);
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<Void> resetPassword(@PathVariable UUID id, @Valid @RequestBody ResetPasswordRequest request,
                                              CurrentUser actor) {
        adminUserService.resetPassword(id, request.password(), actor);
        return ResponseEntity.noContent().build();
    }
}
