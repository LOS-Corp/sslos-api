package com.laundry.auth;

public record LoginResponse(String accessToken, CustomerResponse customer, String role) {
}