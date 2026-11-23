package com.laundry.device;

import com.laundry.device.link.DeviceKeys;
import com.laundry.device.link.LinkState;
import com.laundry.locker.LockerRepository;
import com.laundry.machine.Machine;
import com.laundry.machine.MachineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Phía DB của liên kết thiết bị: ghim khóa, trạng thái liên kết, online/offline. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceRegistry {

    /** Bo mới chờ duyệt cùng lúc; vượt thì từ chối đăng ký cho tới khi admin duyệt/xóa bớt. */
    static final int MAX_PENDING = 50;

    private final DeviceRepository devices;
    private final MachineRepository machines;
    private final LockerRepository lockers;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public enum HelloResult { OK, AUTH_FAILED, REVOKED, TOO_MANY_PENDING }

    /**
     * Bo đã chứng minh giữ khóa {@code publicKey}. Lần đầu: ghim khóa, trạng thái PENDING (chờ admin so dấu vân tay).
     * Đã ghim khóa khác: AUTH_FAILED (bo bị reset nhà máy -> admin xóa bo rồi duyệt lại).
     */
    @Transactional
    public HelloResult onHello(String deviceId, String firmware, String deviceType, byte[] publicKey, String ip,
                               Integer rssi) {
        Optional<Device> found = devices.lock(deviceId);
        Device d;
        if (found.isEmpty()) {
            if (devices.countByLinkState(Device.LinkState.PENDING) >= MAX_PENDING) return HelloResult.TOO_MANY_PENDING;
            d = new Device();
            d.setId(deviceId);
            d.setDeviceType(parseType(deviceType));
            d.setPublicKey(DeviceKeys.encode(publicKey));
            String fp = DeviceKeys.fingerprint(publicKey);
            d.setKeyFingerprint(DeviceKeys.shortFingerprint(fp));
            log.info("[IOT] New board {} ({}) key {} waiting for approval", deviceId, d.getDeviceType(), d.getKeyFingerprint());
        } else {
            d = found.get();
            boolean sameKey = Arrays.equals(DeviceKeys.decode(d.getPublicKey()), publicKey);
            if (!sameKey && d.getLinkState() == Device.LinkState.REVOKED) {
                // Thiết bị đã thu hồi (ví dụ đem bảo hành, về bị thay chip/xóa flash): ghim khóa mới nhưng vẫn REVOKED,
                // nên chưa nhận được tài khoản MQTT cho tới khi admin duyệt lại và đối chiếu mã XT mới trên màn hình.
                // Giữ nguyên dòng devices để không mất hãng, model, giá mua, bảo hành.
                d.setPublicKey(DeviceKeys.encode(publicKey));
                d.setKeyFingerprint(DeviceKeys.shortFingerprint(DeviceKeys.fingerprint(publicKey)));
                log.warn("[IOT] Revoked board {} presented a new key {}; waiting for re-approval", deviceId, d.getKeyFingerprint());
            } else if (!sameKey) {
                log.warn("[IOT] {} presented a key other than the pinned one", deviceId);
                return HelloResult.AUTH_FAILED;
            }
            if (d.getLinkState() == Device.LinkState.REVOKED) {
                d.setLastSeenAt(clock.instant());
                devices.save(d);
                return HelloResult.REVOKED;
            }
        }
        d.setFirmwareVersion(trim(firmware, 40));
        d.setLastIp(trim(ip, 45));
        d.setLastRssi(rssi);
        d.setLastSeenAt(clock.instant());
        devices.save(d);
        return HelloResult.OK;
    }

    @Transactional(readOnly = true)
    public LinkState linkState(String deviceId) {
        return devices.findById(deviceId).map(this::linkState)
                .orElse(LinkState.of(LinkState.State.HELLO_REQUIRED, deviceId));
    }

    LinkState linkState(Device d) {
        return switch (d.getLinkState()) {
            case PENDING -> LinkState.of(LinkState.State.PENDING, d.getId());
            case VERIFIED -> LinkState.of(LinkState.State.VERIFIED, d.getId());
            case REVOKED -> LinkState.of(LinkState.State.REVOKED, d.getId());
            case BOUND -> {
                if (d.getMachineId() != null) {
                    Optional<Machine> m = machines.findById(d.getMachineId());
                    yield m.map(x -> LinkState.bound(d.getId(), x.getCode(), x.getMachineType().name()))
                            .orElse(LinkState.of(LinkState.State.VERIFIED, d.getId()));
                }
                yield lockers.findById(d.getLockerId())
                        .map(l -> LinkState.bound(d.getId(), l.getCode(), "LOCKER"))
                        .orElse(LinkState.of(LinkState.State.VERIFIED, d.getId()));
            }
        };
    }

    @Transactional
    public void connectivity(String deviceId, boolean online) {
        devices.findById(deviceId).ifPresent(d -> {
            boolean changed = d.isOnline() != online;
            d.setOnline(online);
            d.setLastSeenAt(clock.instant());
            if (changed && d.getLinkState() == Device.LinkState.BOUND) {
                events.publishEvent(new DeviceEvents.Connectivity(d.getId(), d.getMachineId(), d.getLockerId(), online));
            }
        });
    }

    @Transactional
    public void allOffline() {
        devices.markAllOffline();
    }

    private static Device.Type parseType(String raw) {
        try {
            return Device.Type.valueOf(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Device.Type.WASHER;
        }
    }

    private static String trim(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
