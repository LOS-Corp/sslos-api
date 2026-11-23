package com.laundry.kiosk;

import com.laundry.audit.AuditService;
import com.laundry.common.Reasons;
import com.laundry.common.Tokens;
import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.security.Actor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Khóa kiosk: sinh một lần, chỉ trả bản gốc lúc tạo/đổi; DB lưu SHA-256. */
@Service
@RequiredArgsConstructor
public class KioskAuthService {

    private static final long TOUCH_EVERY_MS = 60_000;

    private final KioskRepository kiosks;
    private final AuditService audit;
    private final Clock clock;
    private final Map<UUID, Long> lastTouch = new ConcurrentHashMap<>();

    @Transactional
    public Optional<Actor.Kiosk> authenticate(String apiKey) {
        Optional<Kiosk> kiosk = kiosks.findByApiKeyHash(Tokens.sha256Hex(apiKey)).filter(Kiosk::isActive);
        kiosk.ifPresent(k -> {
            long now = clock.millis();
            Long last = lastTouch.get(k.getId());
            if (last == null || now - last > TOUCH_EVERY_MS) {
                lastTouch.put(k.getId(), now);
                kiosks.touch(k.getId(), Instant.ofEpochMilli(now));
            }
        });
        return kiosk.map(k -> new Actor.Kiosk(k.getId(), k.getCode()));
    }

    public record KioskView(UUID id, String code, String name, String status, Instant lastSeenAt) {
        static KioskView of(Kiosk k) {
            return new KioskView(k.getId(), k.getCode(), k.getName(), k.getStatus(), k.getLastSeenAt());
        }
    }

    /** Trả về khóa gốc đúng một lần. */
    public record KioskWithKey(KioskView kiosk, String apiKey) {
    }

    public List<KioskView> list() {
        return kiosks.findAll().stream().map(KioskView::of).toList();
    }

    @Transactional
    public KioskWithKey create(String code, String name) {
        if (kiosks.existsByCode(code)) throw new BusinessException(ErrorCode.CONFLICT, "Mã kiosk đã tồn tại");
        Kiosk k = new Kiosk();
        k.setCode(code);
        k.setName(name);
        String key = "kio_" + Tokens.random(32);
        k.setApiKeyHash(Tokens.sha256Hex(key));
        kiosks.save(k);
        audit.user("KIOSK_CREATED", "kiosk", k.getId().toString(), "Tạo kiosk " + code, null, Map.of("code", code));
        return new KioskWithKey(KioskView.of(k), key);
    }

    @Transactional
    public KioskWithKey rotateKey(UUID id, String reason) {
        Kiosk k = kiosks.findById(id).orElseThrow(() -> BusinessException.notFound("kiosk"));
        String key = "kio_" + Tokens.random(32);
        k.setApiKeyHash(Tokens.sha256Hex(key));
        audit.user("KIOSK_KEY_ROTATED", "kiosk", id.toString(), Reasons.require(reason), null, null);
        return new KioskWithKey(KioskView.of(k), key);
    }

    @Transactional
    public KioskView setStatus(UUID id, boolean active, String reason) {
        Kiosk k = kiosks.findById(id).orElseThrow(() -> BusinessException.notFound("kiosk"));
        String before = k.getStatus();
        k.setStatus(active ? "ACTIVE" : "DISABLED");
        audit.user("KIOSK_STATUS", "kiosk", id.toString(), Reasons.require(reason),
                Map.of("status", before), Map.of("status", k.getStatus()));
        return KioskView.of(k);
    }
}
