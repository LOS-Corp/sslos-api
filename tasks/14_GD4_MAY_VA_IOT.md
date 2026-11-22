# GĐ4 · Máy, quyền START, IoT (`machine`, `devicelink`, `iot`, `websocket`)

Mục tiêu: tách các class lớn nhất, giao thức với firmware có tài liệu và test hợp đồng, xóa code cân thừa.

---

## MA-01 · Tách `MachineStartAuthorizationService` (751 dòng)
- [ ] `CheckInService`: các bước kiểm tra khi kiosk check-in và kích hoạt máy (mục 8 của [00_NGHIEP_VU.md](00_NGHIEP_VU.md)). Mỗi bước là một rule có tên và mã lỗi riêng, test được độc lập.
- [ ] `StartHandshakeService`: xử lý `AUTH_ACK`, `START_REQUEST` → `START_APPROVAL`, `STARTED`, `START_FAILED`.
- [ ] `StartAuthorizationExpiry`: hết hạn và thu hồi quyền START (gộp với `StartAuthorizationExpiryScheduler`).
- [ ] Giữ nguyên `MachineStartAuthorizationServiceTest` (33 test). Chuyển test về đúng class mới, không xóa test nào.

## MA-02 · Một nguồn sự thật cho trạng thái máy
- [ ] Rà mối quan hệ giữa `machines.status`, `MachineRuntimeState` và `allocation.cycle_state`. Ghi rõ dưới task này: ai ghi, ai đọc, cái nào là chuẩn.
- [ ] Viết sơ đồ trạng thái máy **đúng với code** (thay sơ đồ cũ trong README, V-13).
- [ ] Bỏ các chỗ ghi trùng.

## MA-03 · Tách `MachineService` (477) và `MachineRuntimeStateService` (461)
- [ ] `MachineService`: CRUD/quản trị máy tách khỏi phần xử lý sự kiện thiết bị.
- [ ] `MachineRuntimeStateService`: phần tính toán thuần chuyển vào `domain`.

## MA-04 · Tài liệu và test hợp đồng giao thức với firmware
Firmware: `sslos-iot/common/BackendLink.h`, `LaundryOS_GA25`, `LaundryOS_DRYER`, `LaundryOS_RETROFIT`.
- [ ] Viết `docs/device-protocol.md`, gồm:
  - topic `laundryos/v1/devices/<deviceId>/{up,down}/...`;
  - từng loại tin nhắn lên (`status`, `telemetry`, `heartbeat`, `event`, `auth`, `command/ack`) và xuống (`AUTHORIZE_START`, `REVOKE_START`, `START_APPROVAL`, `STOP`, `RESET`, `STATUS`, `SELF_TEST`, `EXTEND_CYCLE`), kèm JSON mẫu.
- [ ] Test hợp đồng: đọc các JSON mẫu đó và kiểm tra `WasherPayloads` / handler parse đúng.
- [ ] Đối chiếu danh sách lệnh trong README (START, ESTOP, PAUSE...) với lệnh firmware thật nhận. Lệnh nào bo không hiểu thì xóa khỏi API hoặc đánh dấu rõ.

## MA-05 · Xóa phần cân của tự phục vụ (V-05)
Phụ thuộc: **Q-06 = xóa**. Làm trước DB-05.
- [ ] Xóa `LoadPolicy`, `MachineLoadService`, `MachineLoadController`, `LoadTopUpService`, `LoadTopUpPaid`, `LoadStatusResponse`.
- [ ] `AuthChannelHandler`: bỏ xử lý `LOAD_WEIGHED` (bo nào gửi thì trả lỗi rõ ràng hoặc bỏ qua, có log).
- [ ] `MachineStartAuthorization`: bỏ các field `measured_load_kg`, `load_verdict`, `load_extra_kg`, `load_checked_at`.
- [ ] Xóa / cập nhật `MachineLoadIT`.
- [ ] Báo frontend (customer-web, kiosk) bỏ màn hình cân (ghi vào 16_GD6 / OT-07).

## MA-06 · `iot`: đăng ký, nghiệm thu bo
- [ ] Tách `DeviceRegistryService` (402) và `CommissioningService` (285) theo use case: đăng ký, duyệt, gắn vào máy, self-test.
- [ ] SQL về repository, dùng `Clock`.

## MA-07 · `Clock` và SQL trong `machine` / `iot` / `devicelink` (V-09, V-10)
- [ ] Liệt kê các file còn vi phạm, rồi sửa hết. Gỡ khỏi allowlist ArchUnit.

**Xong khi:** allowlist `now()` của ArchUnit rỗng (tính cả GĐ2).
