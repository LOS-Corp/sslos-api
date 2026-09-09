package com.laundry.auth;

import com.laundry.user.User;

import java.util.UUID;

public record CustomerResponse(UUID userId, String fullName, String email, String phone) {

    public static CustomerResponse from(User user) {
        return new CustomerResponse(user.getId(), user.getFullName(), user.getEmail(), user.getPhone());
    }
}