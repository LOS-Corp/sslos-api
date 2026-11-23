package com.laundry.device;

import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.device.link.DeviceIds;
import com.laundry.device.link.DeviceKeys;
import com.laundry.device.link.MqttProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bo tự lấy tài khoản broker qua HTTP trong LAN cửa hàng (sslos-iot/common/NetLink.h):
 * <ol>
 *   <li>challenge: nonce dùng một lần (1 phút), lưu device_challenges;</li>
 *   <li>register: chữ ký LAUNDRYOS-REGISTER|id|nonce chứng minh khóa; lần đầu ghim khóa (chờ duyệt), sau đó so khớp;
 *       bo nhận mật khẩu MQTT niêm phong bằng khóa của nó (ECDH P-256 + AES-256-GCM).</li>
 * </ol>
 * Ai trong LAN cũng gọi được, nên có giới hạn tần suất theo IP và giới hạn số bo chờ duyệt. Tài khoản của bo chưa
 * duyệt cũng vô dụng: chỉ dùng được topic của chính nó và backend bỏ qua tin của bo chưa gắn máy.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceRegistrationService {

    static final int NONCE_TTL_SECONDS = 60;
    static final int MAX_REQUESTS_PER_MINUTE = 30;

    private final DeviceRegistry registry;
    private final MqttAccountService accounts;
    private final MqttProperties mqtt;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Map<String, Deque<Long>> requests = new ConcurrentHashMap<>();

    public record Challenge(String nonce, int expiresInSeconds) {
    }

    public record Account(String state, String username, int mqttPort, String epk, String iv, String ct) {
    }

    public record Refused(String state, String message) {
    }

    @Transactional
    public Challenge challenge(String rawDeviceId, String clientAddress) {
        throttle(clientAddress);
        String deviceId = DeviceIds.normalize(rawDeviceId);
        if (!DeviceIds.isValid(deviceId)) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "deviceId không hợp lệ");
        Instant now = clock.instant();
        jdbc.update("delete from device_challenges where expires_at < ? or (device_id = ? and purpose = 'REGISTER')",
                Timestamp.from(now), deviceId);
        String nonce = DeviceKeys.newNonce();
        jdbc.update("insert into device_challenges (nonce, device_id, purpose, expires_at) values (?, ?, 'REGISTER', ?)",
                nonce, deviceId, Timestamp.from(now.plusSeconds(NONCE_TTL_SECONDS)));
        return new Challenge(nonce, NONCE_TTL_SECONDS);
    }

    /** Thành công: tài khoản niêm phong. Từ chối (khóa khác đã ghim / bị thu hồi): {@link Refused}. */
    @Transactional(noRollbackFor = BusinessException.class)
    public Object register(String rawDeviceId, String publicKey, String nonce, String signature, String firmware,
                           String deviceType, Integer rssi, String clientAddress) {
        throttle(clientAddress);
        String deviceId = DeviceIds.normalize(rawDeviceId);
        Instant now = clock.instant();
        // Dùng một lần, kể cả khi chữ ký sai.
        List<String> found = jdbc.queryForList("""
                delete from device_challenges where nonce = ? and device_id = ? and purpose = 'REGISTER'
                  and expires_at >= ? and used_at is null returning nonce
                """, String.class, nonce, deviceId, Timestamp.from(now));
        if (found.isEmpty()) throw new BusinessException(ErrorCode.INVALID_TOKEN, "Nonce không đúng hoặc đã hết hạn");
        byte[] key = DeviceKeys.decodePublicKey(publicKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED, "publicKey không phải điểm P-256"));
        if (!DeviceKeys.verify(key, DeviceKeys.registerMessage(deviceId, nonce), signature)) {
            log.warn("[IOT] Registration of {} from {}: bad signature", deviceId, clientAddress);
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "Chữ ký không khớp khóa");
        }
        DeviceRegistry.HelloResult result = registry.onHello(deviceId, firmware, deviceType, key, clientAddress, rssi);
        switch (result) {
            case AUTH_FAILED -> {
                return new Refused("AUTH_FAILED", "Bo đã được ghim một khóa khác; admin cần xóa bo rồi duyệt lại");
            }
            case REVOKED -> {
                return new Refused("REVOKED", "Bo đã bị thu hồi; admin cần duyệt lại");
            }
            case TOO_MANY_PENDING -> throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "Quá nhiều bo đang chờ duyệt; duyệt hoặc xóa bớt trước");
            default -> {
            }
        }
        String password = accounts.issue(deviceId);
        DeviceKeys.Sealed sealed = DeviceKeys.seal(key, deviceId, password);
        String state = registry.linkState(deviceId).state().name();
        log.info("[IOT] {} registered from {} ({}): broker account issued", deviceId, clientAddress, state);
        return new Account(state, deviceId, mqtt.deviceTlsPort(), sealed.epk(), sealed.iv(), sealed.ct());
    }

    private void throttle(String address) {
        long now = clock.millis();
        Deque<Long> times = requests.computeIfAbsent(address == null ? "?" : address, a -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > 60_000) times.pollFirst();
            if (times.size() >= MAX_REQUESTS_PER_MINUTE) throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
            times.addLast(now);
        }
        if (requests.size() > 10_000) requests.clear();
    }
}
