# GĐ1 · Database

Mục tiêu: schema sạch, nhất quán, đúng với nghiệp vụ hiện tại. Mỗi task là **một migration mới**. Không sửa migration cũ, trừ khi Q-08 chốt là gộp baseline.

Quy trình cho mọi task: viết migration → chạy thử trên bản sao DB thật (PR-04) → `verify` xanh → cập nhật `database.sql` snapshot.

---

## DB-01 · Rà toàn bộ schema
- [ ] Liệt kê mọi bảng và cột hiện có từ `database.sql`, đánh dấu: **đang dùng / không còn dùng / chưa rõ**.
- [ ] Với mỗi cột "không còn dùng", tìm trong code (`grep`) để xác nhận không còn đọc hay ghi.
- [ ] Liệt kê các cột thời gian còn `TIMESTAMP` không múi giờ (V-08).
- [ ] Liệt kê các cột tiền còn `NUMERIC`/`DECIMAL` (V-06).

**Đầu ra:** bảng rà soát ở cuối file này. DB-02 đến DB-08 làm dựa trên bảng đó.

## DB-02 · Tiền chuyển sang `BIGINT` VND (V-06)
Phụ thuộc: DB-01. Làm từng nhóm bảng, mỗi nhóm một PR:
- [ ] `transactions.total_amount`, `payments.amount` → `total_amount_vnd`, `amount_vnd`.
- [ ] `service_offerings.price`, `price_profiles.base_price`, `price_profiles.extra_price`.
- [ ] `dry_extensions.price`, `booking_weight_charges.amount`, `booking_weight_charges.price_per_kg`.
- [ ] `scheduled_bookings.price_per_kg` (nếu còn dùng sau Q-06).

Mỗi nhóm:
- Migration kiểm tra trước là không có giá trị lẻ đồng (`WHERE x <> trunc(x)`); có thì dừng lại và báo lỗi.
- Entity/DTO đổi sang `Money`.
- API JSON giữ nguyên tên trường và kiểu số nguyên, để frontend không phải sửa. Nếu buộc phải đổi tên trường thì ghi vào phần thay đổi API trong `16_GD6`.

**Xong khi:** `grep -r "NUMERIC\|DECIMAL"` trên `database.sql` chỉ còn các cột không phải tiền (kg, chi phí tài sản).

## DB-03 · Thời gian sang `TIMESTAMPTZ` (V-08)
- [ ] Chuyển mọi cột còn lại trong danh sách của DB-01 bằng `ALTER ... TYPE TIMESTAMPTZ USING col AT TIME ZONE 'UTC'`. Trước đó xác nhận dữ liệu cũ thực sự được lưu theo UTC.
- [ ] Entity tương ứng dùng `Instant`.

## DB-04 · Xóa bảng/cột cũ (V-07)
Phụ thuộc: DB-01.
- [ ] `service_types` và `transactions.service_type`: thay bằng enum hoặc `CHECK` trên `transactions`, hoặc xóa nếu không còn dùng.
- [ ] Các cột đã đánh dấu "không còn dùng" trong DB-01.
- [ ] `app_settings`: còn khóa nào được dùng không? Không còn thì xóa bảng.

## DB-05 · Xóa phần cân của khâu tự phục vụ (V-05)
Phụ thuộc: **Q-06 = xóa**, và task code `MA-05` phải xong trước.
- [ ] Xóa các cột `measured_load_kg`, `load_verdict`, `load_extra_kg`, `load_checked_at` khỏi `machine_start_authorizations`, cùng ràng buộc `chk_msa_load_verdict`.
- [ ] Xóa bảng `booking_load_topups`. Trước đó kiểm tra còn dòng PENDING hoặc PAID chưa đối soát không.
- [ ] Giữ `booking_weight_charges` (dùng cho giặt hộ).

## DB-06 · Chuẩn hóa tên ràng buộc và index
- [ ] Đổi tên theo quy ước PR-05 (`pk_`, `fk_`, `uq_`, `chk_`, `idx_`). Chỉ là đổi tên, không đổi logic.
- [ ] Thêm index thiếu cho các khóa ngoại hay được join, ví dụ `payments.transaction_id` (đã có), `booking_resource_allocations.booking_id` (đã có). Kiểm tra bằng `pg_stat_user_indexes` / `EXPLAIN` trên DB thật.
- [ ] Xóa index trùng nhau hoặc không bao giờ được dùng.

## DB-07 · Ràng buộc nghiệp vụ còn thiếu
- [ ] `payments.status` / `transactions.status` khớp với enum trong code (`PaymentStatus`, `TransactionStatus`).
- [ ] `scheduled_bookings.status`: `CHECK` có đủ `NO_SHOW` (V47) và các trạng thái mới của GĐ5.
- [ ] `payments.transaction_id` chưa có khóa ngoại tới `transactions` (V12 không khai báo, và chưa migration nào thêm). Dọn dòng mồ côi, rồi thêm FK.
- [ ] Các FK của `refund_requests` (V53) đang `NOT VALID`. Dọn dòng mồ côi, rồi chạy `VALIDATE CONSTRAINT`.

## DB-08 · Gộp baseline (tùy chọn)
Phụ thuộc: **Q-08**, DB-02 đến DB-07.
- [ ] Nếu chốt gộp: tạo `V1__baseline.sql` từ schema sạch và chuyển V1–V7x vào `db/migration-archive/`. Hướng dẫn `flyway baseline` cho DB cũ.
- [ ] Nếu chốt không gộp: ghi lý do vào đây và gạch task.

## DB-09 · Cập nhật tài liệu schema
- [ ] Sơ đồ ER (Mermaid) trong `docs/database.md`: các bảng chính, khóa ngoại, trạng thái.
- [ ] Mô tả từng bảng: dùng làm gì, module nào ghi, module nào đọc.

---

### Bảng rà soát (điền ở DB-01)

| Bảng | Cột | Kiểu hiện tại | Trạng thái | Hành động |
|---|---|---|---|---|
| | | | | |
