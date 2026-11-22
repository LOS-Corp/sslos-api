# GĐ2 · Module đặt chỗ (`booking`)

Mục tiêu: sửa V-01 và V-02, tách các class lớn, đưa SQL về `repository`, dùng `Clock` thay cho `now()`.
BK-01 và BK-02 **đổi hành vi** (sửa lỗi nghiệp vụ). Các task còn lại là refactor thuần, không được đổi hành vi.

---

## BK-01 · Gộp hai con số check-in (V-01) — đổi hành vi
Phụ thuộc: **Q-01**.
- [ ] `CheckInWindowPolicy.windowFor` nhận thêm `checkInMinutes` của cửa hàng (đọc từ `store_settings`), thay cho `closesAfter` cố định.
- [ ] Sửa 3 nơi đang dùng `props.checkInClosesAfter()`:
  - [ ] `MachineStartAuthorizationService` (bước 6)
  - [ ] `NoShowJob.candidates` + `windowClosesAt`
  - [ ] `MachineSlotService.earlierTurnStillOpen`
- [ ] Xóa `checkInClosesAfter` khỏi `BookingProperties`. Giữ `checkInOpensBefore`.
- [ ] Trường hợp `check_in_minutes = 0`: định nghĩa rõ (cửa sổ đóng đúng lúc T), có test riêng.
- [ ] Cập nhật `CheckInWindowPolicyTest` (bảng mốc biên: T−15, T, T+check_in, T+check_in+1s) và `NoShowIT`.
- [ ] Đối chiếu với mục 6 của [00_NGHIEP_VU.md](00_NGHIEP_VU.md) (ân hạn, thời hạn bấm START, khoảng giữ máy).

**Xong khi:** đặt `check_in_minutes = 5` thì kích hoạt lúc T+5 được, lúc T+5:01 bị từ chối, và NoShowJob đánh NO_SHOW sau T+5.

## BK-02 · Máy không được chạy quá khoảng đã giữ (V-02) — đổi hành vi
Phụ thuộc: BK-01.
- [ ] Hạn của quyền START (`expiresAt`) không được vượt quá lúc cửa sổ check-in đóng. Tức là `expiresAt = min(lúc tạo + TTL, lúc đóng cửa sổ)`.
- [ ] `START_APPROVAL` bị từ chối khi đã quá cửa sổ check-in, kể cả khi quyền START vẫn còn hạn theo TTL cũ.
- [ ] Test: kích hoạt lúc T+4:50 với TTL 60 giây, khách bấm START lúc T+5:10 thì bị từ chối, và màn hình máy báo hết hạn.
- [ ] Ghi vào ADR mới (`docs/adr/010-...`): vì sao chọn giới hạn thời điểm START thay vì dời `occupied_until`. Dời mốc có thể đụng ràng buộc `EXCLUDE` với lượt sau.

## BK-03 · Tách `ScheduledBookingService` (513 dòng)
- [ ] Liệt kê các use case trong class, ghi dưới task này.
- [ ] Tách thành service theo use case, ví dụ: `HoldService` (giữ chỗ + kiểm tra BR-02/26/27), `BookingConfirmationService` (xác nhận khi thanh toán, tiền về muộn BR-04), `BookingQueryService` (đọc đơn và lịch sử).
- [ ] Giữ nguyên public API của controller.

## BK-04 · Tách `BookingAvailabilityService` (621 dòng)
- [ ] Phần tính toán thuần (khung giờ trống, hạn mức 70%, sức chứa) chuyển vào `domain`, có unit test không cần DB.
- [ ] Phần đọc dữ liệu chuyển vào `repository`.

## BK-05 · SQL về `repository` (V-10, phần booking)
- [ ] `NoShowJob`: tách truy vấn `candidates` và `machineFailed` ra `BookingNoShowRepository`.
- [ ] `MachineSlotService`: chuyển sang repository.
- [ ] `BookingResourceQueries` (447 dòng): đổi thành một hoặc nhiều repository rõ trách nhiệm.
- [ ] Các service và job khác trong `booking/service` có `JdbcTemplate`: liệt kê, rồi chuyển hết.

**Xong khi:** `grep JdbcTemplate src/main/java/com/laundry/booking/service` không còn kết quả.

## BK-06 · Trạng thái đơn là enum + máy trạng thái
- [ ] Tạo `BookingStatus` enum (HELD, CONFIRMED, CHECKED_IN, IN_PROGRESS, TRANSFER_PENDING, COMPLETED, CANCELLED, EXPIRED, NO_SHOW, PAYMENT_REVIEW_REQUIRED).
- [ ] Tạo `BookingTransitions` trong `domain`: bảng các chuyển trạng thái hợp lệ, kèm unit test.
- [ ] Mọi `UPDATE scheduled_bookings SET status = '...'` đi qua một hàm duy nhất có điều kiện `WHERE status = <trạng thái cũ>`. Hàm này đã có trong `NoShowJob`; dùng chung cho các chỗ còn lại.

## BK-07 · `Clock` thay cho `now()` trong booking (V-09)
- [ ] `BookingAvailabilityService`, `BookingLifecycleService`, `BookingResourceQueries`, `DryExtensionService`, `LoadMoveService`, `ScheduledBookingService`.
- [ ] Gỡ các file này khỏi allowlist ArchUnit (PR-06).

## BK-08 · Phân định tham số: cửa hàng chỉnh hay cấu hình hệ thống
- [ ] Ghi rõ trong `00_NGHIEP_VU.md` tham số nào chủ cửa hàng chỉnh (`store_settings`) và tham số nào cố định trong `app.booking.*`.
- [ ] Chuyển `BookingProperties` từ package `config` sang `booking.config`.
- [ ] Cân nhắc đưa `freeCancellationBefore` và `advanceQuotaPercent` vào `store_settings`, nếu chủ cửa hàng cần chỉnh.

## BK-09 · Dọn `DryExtensionService` (394 dòng) và `TransferExpiryJob`
- [ ] Tách phần thanh toán mua thêm phút khỏi phần gửi lệnh `EXTEND_CYCLE`.
- [ ] `TransferExpiryJob`: chuyển SQL sang repository, dùng `Clock`.
