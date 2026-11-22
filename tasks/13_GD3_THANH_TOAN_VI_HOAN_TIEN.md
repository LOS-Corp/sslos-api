# GĐ3 · Thanh toán, ví, hoàn tiền (`payment`, `wallet`, `refund`)

Mục tiêu: tiền dùng `Money` ở mọi nơi, có một luồng hoàn tiền duy nhất, và chuẩn bị nền cho việc một đơn có nhiều lần thanh toán (cọc + phần còn lại, ở GĐ5).
Đây là phần rủi ro nhất: mọi task ở đây đều phải có test IT về số tiền trước và sau.

---

## PM-01 · `Money` thay `BigDecimal` (V-06, phần code)
Phụ thuộc: DB-02 (từng nhóm bảng tương ứng).
- [ ] `payment`: `Payment`, `PaymentService`, `PaymentWebhookService`, `PayOSGateway`, các DTO.
- [ ] `refund`: `RefundRequest`, `RefundService`, `AutomaticRefundService`.
- [ ] `transaction`: `Transaction`.
- [ ] `booking`/`machine`: giá profile, mua thêm phút, giặt hộ theo kg.
- [ ] Ở ranh giới payOS (số nguyên VND) chỉ chuyển đổi một lần, trong `PayOSGateway`.

**Xong khi:** số file dùng `BigDecimal` cho tiền bằng 0. Những chỗ dùng `BigDecimal` cho kg thì ghi tên vào đây.

## PM-02 · Tách `PaymentService` (364 dòng)
- [ ] Tách thành `PaymentLinkService` (tạo link payOS), `WalletFirstCheckout` (trừ ví trước, phần còn lại qua payOS, hoàn phần ví khi payOS thất bại), và `PaymentQueryService`.
- [ ] Phần áp kết quả webhook nằm hết trong `PaymentWebhookService`.

## PM-03 · Thiết kế "một đơn, nhiều khoản thu" (nền cho GĐ5)
Hiện mỗi thứ phải thu tiền có một `transaction` riêng: đơn, mua thêm phút (`dry_extensions`), tiền kg giặt hộ (`booking_weight_charges`), mua thêm kg (`booking_load_topups`).
- [ ] Viết ADR: giữ mô hình "mỗi khoản thu là một transaction gắn với booking", và thêm cột `purpose` (`BOOKING_FULL`, `DEPOSIT`, `BALANCE`, `DRY_EXTENSION`, `WEIGHT_CHARGE`).
- [ ] Migration: `transactions.booking_id` (nullable, có FK) + `purpose`. Backfill từ các bảng hiện có.
- [ ] Một truy vấn duy nhất trả lời câu hỏi "đơn X đã thu được bao nhiêu, còn thiếu bao nhiêu". Dùng cho hoàn tiền (trần BR-14) và cho GĐ5.

## PM-04 · Webhook payOS chặt chẽ
- [ ] Kiểm tra chữ ký, `orderCode` khớp một thanh toán, **số tiền khớp đúng**, và xử lý mỗi sự kiện đúng một lần (khóa duy nhất theo mã giao dịch payOS).
- [ ] IT: webhook gửi lặp 2 lần → chỉ áp một lần. Sai số tiền → không xác nhận, chuyển sang review. Webhook đến sau khi đơn đã hủy → `LATE_PAYMENT_CREDIT` (đã có, giữ nguyên).
- [ ] Lưu `counterAccount...` (tài khoản người chuyển) nếu payOS gửi kèm. Dùng cho hoàn tiền khách vãng lai về sau (Q-05).

## PM-05 · Một cửa hoàn tiền
Hiện có `AutomaticRefundService` (hủy, không đến, lỗi hệ thống), `RefundService` (yêu cầu hoàn và duyệt), và yêu cầu hoàn của kiosk.
- [ ] Một hàm duy nhất `refund(transaction, amount, cause, destination)`. Nó áp trần BR-14 và chọn đích: ví của khách, hoặc yêu cầu trả tại quầy cho đơn của kiosk.
- [ ] Mọi nơi đang tự cộng ví hay tự tạo yêu cầu hoàn đều gọi qua hàm này.
- [ ] Giữ test `RefundCapIT`, `MoneyFlowIT`.

## PM-06 · Gộp controller giả lập thanh toán
- [ ] `LocalPaymentSimulationController` và `KioskPaymentSimulationController` gộp thành một. Chỉ bật ở profile `dev` cùng mock gateway.
- [ ] IT: profile `prod` không có endpoint giả lập.

## PM-07 · Ví và rút tiền
- [ ] Rà `WalletService`, `WithdrawalService`, `WalletReconciliationJob`: dùng `Money`, dùng `Clock`, SQL nằm trong repository.
- [ ] Không đổi hành vi: sổ cái chỉ-thêm, giữ tiền khi rút, bắt buộc chứng từ.
