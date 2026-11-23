package com.laundry.notification;

import com.laundry.common.Money;
import com.laundry.order.Order;
import com.laundry.refund.Refund;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Thông báo cho khách (§17). Khách có tài khoản: trong app (IN_APP). Khách vãng lai có số điện thoại: SMS.
 * Mỗi thông báo một dedupe_key, gọi lại không tạo trùng; nhắc lịch là dòng có scheduled_at trong tương lai.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm dd/MM").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final NotificationRepository repository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** Xếp một thông báo; trùng dedupeKey thì bỏ qua. */
    @Transactional
    public void enqueue(Notification.Type type, UUID orderId, UUID userId, String phone, String title, String body,
                        Instant at, String dedupeKey) {
        if (userId == null && phone == null) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("body", body);
        Notification.Channel channel = userId != null ? Notification.Channel.IN_APP : Notification.Channel.SMS;
        jdbc.update("""
                insert into notifications (id, user_id, phone, order_id, type, channel, payload, scheduled_at, dedupe_key)
                values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?) on conflict (dedupe_key) do nothing
                """, UUID.randomUUID(), userId, userId == null ? phone : null, orderId, type.name(), channel.name(),
                mapper.writeValueAsString(payload), Timestamp.from(at == null ? clock.instant() : at), dedupeKey);
    }

    public void order(Order o, Notification.Type type, String title, String body, Instant at) {
        enqueue(type, o.getId(), o.getCustomerId(), o.getGuestPhone(), title, body, at,
                "order:" + o.getId() + ":" + type + (at == null ? "" : ":" + at.getEpochSecond()));
    }

    /** Đặt / thanh toán thành công + nhắc trước T 30', đúng T, ân hạn còn 5' (đơn đặt trước, §7.6). */
    public void confirmed(Order o) {
        order(o, Notification.Type.ORDER_CONFIRMED, "Đặt lượt thành công",
                "Đơn " + o.getCode() + " lúc " + HHMM.format(o.getScheduledStartAt()) + ". Đến kiosk quét QR để check-in.",
                null);
        if (o.getChannel() == Order.Channel.APP) {
            Instant t = o.getScheduledStartAt();
            Instant now = clock.instant();
            if (t.minusSeconds(1800).isAfter(now)) {
                order(o, Notification.Type.REMIND_BEFORE_START, "Còn 30 phút", "Đơn " + o.getCode()
                        + " bắt đầu lúc " + HHMM.format(t) + ".", t.minusSeconds(1800));
            }
            if (t.isAfter(now)) {
                order(o, Notification.Type.REMIND_AT_START, "Đến giờ rồi", "Mời bạn đến kiosk quét QR đơn "
                        + o.getCode() + ".", t);
            }
        }
        if (o.getCheckinDeadlineAt() != null && o.getCheckinDeadlineAt().minusSeconds(300).isAfter(clock.instant())) {
            order(o, Notification.Type.GRACE_ENDING, "Sắp hết giờ check-in", "Còn 5 phút để check-in đơn "
                    + o.getCode() + ".", o.getCheckinDeadlineAt().minusSeconds(300));
        }
    }

    /** Đơn đã check-in / hủy: bỏ các nhắc lịch chưa gửi. */
    @Transactional
    public void cancelReminders(UUID orderId) {
        repository.cancelPending(orderId, List.of(Notification.Type.REMIND_BEFORE_START,
                Notification.Type.REMIND_AT_START, Notification.Type.GRACE_ENDING));
    }

    public void refund(Refund r) {
        if (r.getOrderId() == null && r.getRecipientUserId() == null) return;
        String where = switch (r.getDestination()) {
            case WALLET -> "vào ví";
            case BANK_TRANSFER -> "về tài khoản đã chuyển (trong 1 ngày làm việc)";
            case COUNTER -> "tại quầy (mang theo biên nhận)";
        };
        enqueue(Notification.Type.REFUND_ISSUED, r.getOrderId(), r.getRecipientUserId(), guestPhone(r.getOrderId()),
                "Hoàn tiền", "Hoàn " + Money.format(r.getAmount()) + " " + where + ".", null, "refund:" + r.getId());
    }

    public org.springframework.data.domain.Page<Notification> inbox(UUID userId, int page, int size) {
        return repository.inbox(userId, org.springframework.data.domain.PageRequest.of(page, Math.min(size, 100)));
    }

    @Transactional
    public void markRead(UUID id, UUID userId) {
        repository.markRead(id, userId, clock.instant());
    }

    /** Gửi các thông báo đến hạn. IN_APP: hiện trong app. SMS: chưa có nhà cung cấp, ghi log. */
    @Transactional
    public int deliverDue() {
        List<Notification> due = repository.due(clock.instant());
        for (Notification n : due) {
            n.setAttempts(n.getAttempts() + 1);
            switch (n.getChannel()) {
                case IN_APP -> {
                    n.setStatus(Notification.Status.SENT);
                    n.setSentAt(clock.instant());
                }
                case SMS, PUSH, EMAIL -> {
                    log.info("[NOTIFY {}] {} {}", n.getChannel(), n.getPhone(), n.getPayload());
                    n.setStatus(Notification.Status.FAILED);
                    n.setLastError("Chưa cấu hình nhà cung cấp " + n.getChannel());
                }
            }
        }
        return due.size();
    }

    private String guestPhone(UUID orderId) {
        if (orderId == null) return null;
        List<String> phones = jdbc.queryForList("select guest_phone from orders where id = ?", String.class, orderId);
        return phones.isEmpty() ? null : phones.get(0);
    }
}
