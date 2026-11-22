# 01 · Hiện trạng và vấn đề

Khảo sát ngày 23/11/2026: khoảng 280 file Java trong `src/main`, 72 migration Flyway, 8 ADR.
Mỗi vấn đề có mã `V-xx`. Các task trong những file sau tham chiếu lại mã này.

## A. Lỗi nghiệp vụ (ảnh hưởng khách / tiền)

### V-01 · Có hai con số "check-in" lệch nhau
- **Khoảng giữ máy** dùng `store_settings.check_in_minutes`, mặc định 5 phút ([SlotPlanner.java:195](../src/main/java/com/laundry/booking/domain/SlotPlanner.java#L195)).
- **Cửa sổ quét QR / kích hoạt** dùng hằng số `checkInClosesAfter = 10m` ([BookingProperties.java:27](../src/main/java/com/laundry/config/BookingProperties.java#L27)). Không có chỗ nào ghi đè giá trị này trong `application.properties`.
- **Hậu quả khi `check_in_minutes` < 10:** khách kích hoạt lúc T+10 thì máy chạy xong muộn hơn lịch đã giữ, chồng lên check-out, đệm dọn máy, thậm chí lượt sau.
- **Hậu quả khi `check_in_minutes` > 10:** phần chủ cửa hàng cài thêm không có tác dụng, vì QR đã hết hạn từ T+10.

### V-02 · `occupied_until` không được dời khi máy chạy muộn
Chỉ có `DryExtensionService`, `LoadMoveService` và `LockerService` cập nhật `occupied_until`. Khi khách bấm START muộn, mốc này giữ nguyên. Vì vậy bước 7 của check-in ("khách trước chưa lấy đồ", [MachineStartAuthorizationService.java:222](../src/main/java/com/laundry/machine/service/MachineStartAuthorizationService.java#L222)) dựa trên một mốc đã sai.

### V-03 · Kiosk chỉ kích hoạt được đơn do chính kiosk tạo
`KioskService.start` gọi `getBooking(kiosk.id(), …)`, hàm này lọc `t.customer_id = ?` ([ScheduledBookingService.java:210](../src/main/java/com/laundry/booking/service/ScheduledBookingService.java#L210)), rồi gọi `checkIn(machineCode, kiosk.id())`. Đơn đặt trước trên app/web thuộc tài khoản của khách, nên **kiosk không quét được QR đơn của khách đặt trước**.

### V-04 · Chưa có đặt cọc
Không có bảng, cột hay code nào cho cọc hoặc thanh toán nhiều lần cho một đơn. Hiện mọi đơn phải trả đủ 100% trước khi CONFIRMED.

### V-05 · Còn sót tính năng cân của khâu tự phục vụ
V65 đã bỏ tính theo kg cho tự phục vụ, và firmware máy giặt đã tắt cân. Nhưng những phần sau vẫn còn:
- Code: `LoadPolicy`, `MachineLoadService`, `MachineLoadController`, `LoadTopUpService`, sự kiện `LoadTopUpPaid`.
- Xử lý `LOAD_WEIGHED` / `LOAD_VERDICT` trong `AuthChannelHandler`.
- Các cột `measured_load_kg`, `load_verdict`, `load_extra_kg`, `load_checked_at` trên `machine_start_authorizations`.
- Bảng `booking_load_topups`.

Cần quyết định có xóa hay không (Q-06).

## B. Database

### V-06 · Tiền lưu hai kiểu
- `BIGINT` VND: `wallets`, `wallet_entries`.
- `NUMERIC(10,2)` / `DECIMAL(10,2)`: `payments.amount`, `transactions.total_amount`, `service_offerings.price`, `price_profiles.base_price`, `price_profiles.extra_price`, `dry_extensions.price`, `booking_weight_charges.amount`, `booking_load_topups.price_per_kg`, `scheduled_bookings.price_per_kg`.
- Trong code: 66 file dùng `BigDecimal`, chỉ 25 file dùng `Money`. ADR-004 đã chọn số nguyên đồng.

### V-07 · Dấu vết schema cũ
- Có hai migration cùng tên `create_transaction_schema` (V11 chỉ 3 dòng, V13).
- Bảng `service_types` và cột `transactions.service_type` có từ thời luồng mua hàng cũ, nay luồng đó đã bị xóa (V51).
- Nhiều đợt đi rồi quay lại: hãng máy (V22 thêm, V26 xóa, V63 thêm lại), tọa độ (V32, rồi thay bằng V33), chương trình máy (V38, rồi xóa ở V69), cân (V61, rồi bỏ ở V65).
- Tên ràng buộc không thống nhất, ví dụ `chk_...`, `..._check`, `fk_...` trộn lẫn.
- 72 migration làm việc dựng DB mới chậm và khó đọc. Có nên gộp thành baseline? (Q-08)

### V-08 · Cột thời gian
V12/V13 tạo `TIMESTAMP` không có múi giờ. V54 đã chuyển các cột thời gian liên quan đến tiền sang thời điểm tuyệt đối, nhưng **cần rà lại toàn schema** xem còn cột `TIMESTAMP` nào không.

## C. Code

### V-09 · Gọi đồng hồ hệ thống trực tiếp
ADR-005 yêu cầu dùng `Clock` tiêm vào. Thực tế có 37 file gọi `Instant.now()`, `OffsetDateTime.now()` hoặc `LocalDateTime.now()`, ví dụ [MachineStartAuthorizationService.java:214](../src/main/java/com/laundry/machine/service/MachineStartAuthorizationService.java#L214) ngay trong kiểm tra cửa sổ check-in. Hậu quả là không viết được test cho các mốc biên.

### V-10 · SQL viết thẳng trong service
26 file dùng `JdbcTemplate`, phần lớn là `service` và `job` (`NoShowJob`, `ScheduledBookingService`, `MachineSlotService`...). Song song đó có 20 `JpaRepository`. ADR-001 quy định truy cập dữ liệu nằm ở `repository`, nên đây là vi phạm. Cùng một bảng có chỗ đọc bằng JPA, có chỗ đọc bằng SQL.

### V-11 · Class quá lớn

| File | Số dòng |
|---|---|
| `MachineStartAuthorizationService` | 751 |
| `BookingAvailabilityService` | 621 |
| `LockerService` | 603 |
| `ScheduledBookingService` | 513 |
| `MachineService` | 477 |
| `MachineRuntimeStateService` | 461 |
| `BookingResourceQueries` | 447 |
| `DeviceRegistryService` | 402 |
| `DryExtensionService` | 394 |

### V-12 · Trùng lặp và cấu trúc không đồng nhất
- Có hai class `Role`: `shared.security.Role` và `user.entity.Role`.
- Có hai package lỗi: `shared.error` và `shared.exception`. Ngoài ra mỗi module còn có exception riêng.
- DTO tổ chức không thống nhất. Có module chia `dto/request` + `dto/response` (auth, payment, refund), có module dồn tất cả vào một file `XxxDtos` (booking, locker, wallet).
- Package `kiosk` không chia lớp con. `config` chứa cả `BookingProperties`, là tham số nghiệp vụ.
- Hai controller giả lập thanh toán: `LocalPaymentSimulationController` và `KioskPaymentSimulationController`.

### V-13 · Tài liệu lỗi thời
`README.md` còn ghi topic `laundry/washer/...`, sơ đồ trạng thái có `RESERVED` / `WAITING_START`, và lệnh `ESTOP`. Nó cũng dẫn tới `task/09_PHAN_QUYEN_4_VAI_TRO.md`, file này không còn tồn tại, trong khi ADR-003, `SecurityConfig.java` và `Roles.java` vẫn tham chiếu tới nó.

## D. Điểm tốt, cần giữ nguyên

- Ràng buộc `EXCLUDE` chống đặt trùng (V44), cùng `HoldConcurrencyIT`.
- Sổ cái ví chỉ-thêm + `UNIQUE(wallet, type, reference)`.
- Các policy thuần (`SlotPlanner`, `CheckInWindowPolicy`, `RefundPolicy`, `Money`) và test dạng bảng.
- Lệnh xuống thiết bị gửi sau commit (ADR-007).
- Test chạy trên PostgreSQL thật bằng Testcontainers (ADR-009).
- Một mô hình lỗi duy nhất `ApiError` + `ErrorCode` (ADR-006).
