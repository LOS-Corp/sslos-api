package com.laundry.shared;

public interface EmailService {
    void sendPasswordResetEmail(String toEmail, String customerName, String otpCode);
}
