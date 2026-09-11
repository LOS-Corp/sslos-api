package com.laundry.shared;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * SMTP-based email service implementation.
 * Sends actual HTML emails via configured SMTP server.
 */
@Service
public class SmtpEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailService.class);

    private final JavaMailSender mailSender;
    private final String fromEmail;
    private final String appName = "SSLOS";

    public SmtpEmailService(JavaMailSender mailSender,
                           @Value("${spring.mail.from:noreply@sslos.com}") String fromEmail) {
        this.mailSender = mailSender;
        this.fromEmail = fromEmail;
    }

    @Override
    public void sendPasswordResetEmail(String toEmail, String customerName, String otpCode) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("[" + appName + "] Yêu cầu đặt lại mật khẩu");
            helper.setText(buildHtmlEmailBody(customerName, otpCode), true);

            mailSender.send(message);
            log.info("Password reset email sent successfully to: {}", toEmail);
        } catch (MessagingException | MailException e) {
            log.error("Failed to send password reset email to: {}. Error: {}", toEmail, e.getMessage());
            throw new RuntimeException("Failed to send password reset email", e);
        }
    }

    private String buildHtmlEmailBody(String customerName, String otpCode) {
        return """
<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Đặt lại mật khẩu</title>
    <style>
        body {
            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
            background-color: #f4f4f4;
            margin: 0;
            padding: 20px;
        }
        .container {
            max-width: 600px;
            margin: 0 auto;
            background-color: #ffffff;
            border-radius: 12px;
            overflow: hidden;
            box-shadow: 0 4px 20px rgba(0, 0, 0, 0.1);
        }
        .header {
            background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%);
            padding: 30px;
            text-align: center;
            color: white;
        }
        .header h1 {
            margin: 0;
            font-size: 24px;
            font-weight: 600;
        }
        .header p {
            margin: 5px 0 0;
            opacity: 0.9;
            font-size: 14px;
        }
        .content {
            padding: 40px 30px;
            color: #333333;
        }
        .greeting {
            font-size: 16px;
            color: #555;
            margin-bottom: 20px;
        }
        .message {
            font-size: 15px;
            line-height: 1.6;
            color: #444;
            margin-bottom: 30px;
        }
        .otp-container {
            background: linear-gradient(135deg, #f8f9ff 0%%, #f0f4ff 100%%);
            border: 2px dashed #667eea;
            border-radius: 12px;
            padding: 25px;
            text-align: center;
            margin: 25px 0;
        }
        .otp-label {
            font-size: 13px;
            color: #666;
            text-transform: uppercase;
            letter-spacing: 1px;
            margin-bottom: 10px;
        }
        .otp-code {
            font-size: 36px;
            font-weight: bold;
            color: #667eea;
            letter-spacing: 8px;
            margin: 0;
        }
        .warning {
            background-color: #fff3cd;
            border-left: 4px solid #ffc107;
            border-radius: 6px;
            padding: 15px 20px;
            margin: 25px 0;
            font-size: 14px;
            color: #856404;
        }
        .warning-icon {
            margin-right: 8px;
        }
        .footer {
            background-color: #f8f9fa;
            padding: 20px 30px;
            text-align: center;
            border-top: 1px solid #eee;
        }
        .footer p {
            margin: 0;
            font-size: 12px;
            color: #888;
        }
        .footer .app-name {
            font-weight: 600;
            color: #667eea;
        }
        .social-links {
            margin-top: 15px;
        }
        .social-links a {
            color: #667eea;
            text-decoration: none;
            margin: 0 10px;
            font-size: 12px;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>[SSLOS] Đặt lại mật khẩu</h1>
            <p>Hệ thống Giặt Ủi Tự Phục Vụ</p>
        </div>
        
        <div class="content">
            <p class="greeting">Xin chào <strong>%s</strong>,</p>
            
            <p class="message">
                Chúng tôi đã nhận được yêu cầu đặt lại mật khẩu cho tài khoản của bạn tại 
                <strong>Hệ thống Giặt Ủi Tự Phục Vụ</strong>.
            </p>
            
            <p class="message">
                Dưới đây là mã xác minh (OTP) của bạn:
            </p>
            
            <div class="otp-container">
                <p class="otp-label">Mã xác minh</p>
                <p class="otp-code">%s</p>
            </div>
            
            <p class="message">
                Mã này sẽ <strong>hết hạn sau 5 phút</strong>. 
                Vui lòng sử dụng ngay để hoàn tất việc đặt lại mật khẩu.
            </p>
            
            <div class="warning">
                <span class="warning-icon">⚠️</span>
                <strong>Lưu ý bảo mật:</strong> Nếu bạn không yêu cầu đặt lại mật khẩu, 
                vui lòng bỏ qua email này hoặc liên hệ bộ phận hỗ trợ ngay.
            </div>
        </div>
        
        <div class="footer">
            <p>Email này được gửi tự động từ <span class="app-name">SSLOS</span></p>
            <p>Hệ thống Giặt Ủi Tự Phục Vụ</p>
            <div class="social-links">
                <a href="#">Trang chủ</a> | <a href="#">Hỗ trợ</a> | <a href="#">Điều khoản</a>
            </div>
        </div>
    </div>
</body>
</html>
""".formatted(customerName, otpCode);
    }
}
