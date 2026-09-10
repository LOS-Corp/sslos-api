package com.laundry.auth;

public record LoginResponse(String accessToken, String refreshToken, CustomerResponse customer, String role) {
}