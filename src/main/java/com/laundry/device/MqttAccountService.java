package com.laundry.device;

import com.laundry.common.Tokens;
import com.laundry.device.link.DeviceIds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Tài khoản broker trong bảng mqtt_accounts (EMQX xác thực trực tiếp từ bảng này, xem docker/emqx/emqx.conf).
 * Mỗi bo một tài khoản (username = deviceId); backend là superuser.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MqttAccountService {

    private final MqttAccountRepository accounts;
    private final Clock clock;

    /** Cấp (hoặc cấp lại) mật khẩu cho bo; trả mật khẩu gốc để niêm phong gửi về bo. */
    @Transactional
    public String issue(String deviceId) {
        if (!DeviceIds.isValid(deviceId)) throw new IllegalArgumentException("Bad device id " + deviceId);
        String password = Tokens.random(24);
        upsert(deviceId, password, false, deviceId);
        return password;
    }

    @Transactional
    public void disable(String deviceId) {
        accounts.findById(deviceId).ifPresent(a -> a.setEnabled(false));
    }

    /** Tài khoản của chính backend, ghi lại mỗi lần khởi động từ MQTT_BACKEND_USERNAME/PASSWORD. */
    @Transactional
    public void ensureBackendAccount(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.warn("[MQTT] MQTT_BACKEND_USERNAME/PASSWORD not set: the backend cannot log in to the broker");
            return;
        }
        upsert(username, password, true, null);
    }

    private void upsert(String username, String password, boolean superuser, String deviceId) {
        MqttAccount a = accounts.findById(username).orElseGet(MqttAccount::new);
        String salt = Tokens.random(12);
        a.setUsername(username);
        a.setSalt(salt);
        a.setPasswordHash(Tokens.sha256Hex(salt + password));
        a.setSuperuser(superuser);
        a.setDeviceId(deviceId);
        a.setEnabled(true);
        a.setRotatedAt(clock.instant());
        accounts.save(a);
    }
}
