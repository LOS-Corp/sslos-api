package com.laundry.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Logging-based email service for development and testing.
 * Only active when app.email.enabled is false or not set.
 */
@Service
@ConditionalOnProperty(name = "app.email.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailService.class);

    @Override
    public void sendPasswordResetEmail(String toEmail, String customerName, String otpCode) {
        log.info("========== PASSWORD RESET EMAIL ==========");
        log.info("To: {}", toEmail);
        log.info("Customer: {}", customerName);
        log.info("Your OTP code: {}", otpCode);
        log.info("This code expires in 5 minutes.");
        log.info("==========================================");
    }
}
