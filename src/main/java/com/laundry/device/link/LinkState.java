package com.laundry.device.link;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Nội dung down/link gửi cho bo (lúc HELLO, khi thay đổi, và mỗi 10 s làm keepalive).
 * Bo chỉ nhận START khi đang BOUND và liên kết còn mới (sslos-iot/common/BackendLink.h).
 * Không còn qrUrl: màn hình máy chỉ hướng dẫn "Check-in tại kiosk" (§1).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LinkState(State state, String deviceId, String machineCode, String machineType) {

    public enum State { HELLO_REQUIRED, PENDING, VERIFIED, BOUND, REVOKED, DUPLICATE, AUTH_FAILED }

    public static LinkState of(State state, String deviceId) {
        return new LinkState(state, deviceId, null, null);
    }

    public static LinkState bound(String deviceId, String code, String type) {
        return new LinkState(State.BOUND, deviceId, code, type);
    }

    public boolean isBound() {
        return state == State.BOUND && machineCode != null;
    }
}
