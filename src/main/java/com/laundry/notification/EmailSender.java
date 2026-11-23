package com.laundry.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Gửi email; EMAIL_ENABLED=false thì chỉ ghi log (môi trường dev). */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailSender {

    private final ObjectProvider<JavaMailSender> mailSender;

    @Value("${app.email.enabled:false}")
    private boolean enabled;
    @Value("${app.email.from:noreply@sslos.com}")
    private String from;

    /** true khi đã gửi (hoặc đã ghi log ở chế độ dev). */
    public boolean send(String to, String subject, String body) {
        if (!enabled) {
            log.info("[EMAIL disabled] to={} subject={}\n{}", to, subject, body);
            return true;
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.warn("EMAIL_ENABLED=true but no mail sender is configured");
            return false;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            sender.send(message);
            return true;
        } catch (RuntimeException e) {
            log.warn("Cannot send email to {}: {}", to, e.getMessage());
            return false;
        }
    }

    @Async
    public void sendAsync(String to, String subject, String body) {
        send(to, subject, body);
    }
}
