# GĐ5 · Nghiệp vụ mới: cọc 20%, quét QR đơn trên kiosk, trả nốt 80%

Nghiệp vụ: mục 5, 6, 7, 8, 11 của [00_NGHIEP_VU.md](00_NGHIEP_VU.md).
Phụ thuộc: GĐ2 (BK-01, BK-02), GĐ3 (PM-03, PM-04, PM-05), MA-01, và các quyết định Q-01 → Q-04, Q-07, Q-09, Q-10.

```
App/web: giữ chỗ (máy cố định) ─> trả cọc 20% ─> CONFIRMED + QR đơn
Kiosk:   quét QR đơn ─> kiểm tra (cửa sổ, máy rảnh/online) ─> QR payOS 80% ─> webhook đủ tiền ─> AUTHORIZE_START
Máy:     khách bấm START ─> chạy
Hết cửa sổ check-in mà chưa trả đủ ─> NO_SHOW, trả máy, cọc xử lý theo Q-02
```

---

## KO-01 · `checkIn` theo đơn, không theo người gọi (V-03)
- [ ] Đổi `MachineStartAuthorizationService.checkIn(machineCode, userId)` thành `checkIn(bookingId, machineCode, actor)`. `actor` là khách chủ đơn, hoặc kiosk đã xác thực bằng QR đơn.
- [ ] `quyền START` lưu cả `booking_owner_id` và `activated_by` (kiosk hay khách) để audit.
- [ ] Luồng quét QR máy trên app vẫn chạy như cũ (actor = chủ đơn).

## KO-02 · Mô hình dữ liệu cọc
- [ ] `store_settings.deposit_percent` (mặc định 20, `CHECK 0–100`; 0 = tắt cọc). Chủ cửa hàng chỉnh trên web.
- [ ] Đơn đặt trước: `scheduled_bookings.deposit_vnd`, `balance_due_vnd`. Giá chốt lúc giữ chỗ.
- [ ] Khoản thu `purpose = DEPOSIT` và `BALANCE` (theo PM-03).
- [ ] Trạng thái: CONFIRMED nghĩa là "đã giữ được chỗ". Thêm `balance_paid_at`, hoặc tính từ các khoản thu, để biết đã trả đủ chưa. Không thêm trạng thái mới nếu không cần.
- [ ] Đơn đặt ngay: `deposit_vnd = total`, `balance_due_vnd = 0` (không đổi hành vi).
- [ ] Làm tròn xuống theo `Money.percent`. Ghi rõ phần lẻ nằm ở cọc hay ở phần còn lại.

## KO-03 · QR của đơn
- [ ] Bảng `booking_check_in_tokens` (`token_hash`, `booking_id`, `expires_at`, `used_at`). Token ngẫu nhiên 128 bit; DB chỉ lưu hash.
- [ ] Hết hạn lúc cửa sổ check-in đóng (BK-01).
- [ ] `used_at` được ghi khi đã trả đủ và quyền START được cấp. Quét lại sau đó chỉ cấp lại quyền START, không thu thêm tiền (KO-06).
- [ ] API cho khách: `GET /api/bookings/{id}/check-in-qr` (chỉ chủ đơn gọi được, đơn phải CONFIRMED).

## KO-04 · Kiosk quét QR đơn
- [ ] `POST /api/kiosk/check-ins {token}`. Trả về đơn, máy được gán, số tiền còn thiếu, và hạn chót.
- [ ] Các lỗi có mã riêng: chưa đến giờ (kèm giờ mở), hết giờ, đã dùng, máy offline/lỗi (sang KO-07), khách trước chưa lấy đồ.
- [ ] Giữ máy trong lúc khách trả tiền, để không ai khác kích hoạt máy đó.

## KO-05 · Trả nốt 80% trên kiosk
- [ ] `POST /api/kiosk/check-ins/{id}/payment` tạo link payOS cho `balance_due_vnd`, hạn theo Q-10.
- [ ] Webhook trả đủ thì đánh dấu đã trả đủ, rồi gọi `checkIn(bookingId, …, actor = kiosk)` và `AUTHORIZE_START`.
- [ ] Hết hạn link thì hủy link payOS và nhả giữ máy. Tiền về sau hạn: nếu cửa sổ check-in còn mở thì vẫn chấp nhận; nếu đã đóng thì tự hoàn qua PM-05.
- [ ] `balance_due_vnd = 0` (đã trả đủ từ trước): bỏ qua bước thanh toán, cấp quyền START luôn.

## KO-06 · Đã trả nhưng chưa bấm START kịp
- [ ] Quét lại QR đơn trong cửa sổ check-in thì được cấp quyền START mới. Không tạo thanh toán mới.
- [ ] IT: trả xong, để quyền START hết hạn, quét lại, rồi START thành công.

## KO-07 · Chính sách cho đơn có cọc
Phụ thuộc: **Q-02, Q-03, Q-09**.
- [ ] `RefundPolicy` có thêm nhánh cho đơn có cọc: không đến (Q-02), hủy (Q-03). Bảng test cho từng mốc.
- [ ] `NoShowJob`: đơn đã cọc mà chưa trả đủ khi hết cửa sổ thì chuyển NO_SHOW và áp Q-02. Đơn đã trả đủ mà chưa START thì xử lý như hiện nay.
- [ ] Máy được gán bị lỗi: chuyển sang máy theo Q-09 (dùng lại `LoadMoveService` nếu phù hợp). Không có máy nào thì hoàn 100% (BR-13).

## KO-08 · Điều khoản và frontend
- [ ] Điều khoản đặt trước, hiện trước khi khách trả cọc: ân hạn, hạn hủy, không đến, máy lỗi (theo Q-04).
- [ ] `sslos-customer-web`: màn hình trả cọc, hiện QR đơn, đếm ngược cửa sổ check-in.
- [ ] `sslos-kiosk`: màn hình quét QR đơn, hiện số tiền còn thiếu + QR payOS, và hướng dẫn "ra máy X bấm START".
- [ ] `sslos-management-web`: cài `deposit_percent`; danh sách đơn hiện đã cọc / đã trả đủ.

## KO-09 · (Tùy chọn) Hoàn tiền khách vãng lai bằng chi hộ payOS
Phụ thuộc: **Q-05 = dùng chi hộ**, PM-04 (đã lưu tài khoản người chuyển), PM-05.
- [ ] Đăng ký và bật dịch vụ chi hộ payOS. Checksum key riêng nằm trong config `prod`, không commit.
- [ ] `PayoutGateway`: tạo lệnh chi, `x-idempotency-key` = mã hoàn, kiểm tra tài khoản đích (`validateDestination`).
- [ ] Chỉ backend tạo lệnh chi, gắn với một yêu cầu hoàn hợp lệ. Không vượt trần BR-14.
- [ ] Hết số dư tài khoản chi, hoặc thiếu tài khoản người chuyển: quay về yêu cầu trả tại quầy.
