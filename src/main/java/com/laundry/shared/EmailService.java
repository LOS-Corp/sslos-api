package com.laundry.shared;

public interface EmailService {
    void sendPasswordResetEmail(String toEmail, String customerName, String otpCode);

    /** Tells the customer their wash cycle has finished and the laundry is ready to collect. */
    void sendLaundryCompletedEmail(LaundryCompletedEmail mail);
}
