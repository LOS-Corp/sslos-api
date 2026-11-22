# GĐ0 · Chuẩn bị (lưới an toàn)

Mục tiêu: trước khi động vào code, phải có cách **phát hiện ngay** nếu refactor làm đổi hành vi.

---

## PR-01 · Test đang xanh, có số liệu ban đầu
- [ ] Chạy `./mvnw -B verify` trên máy có Docker. Ghi lại số test, thời gian chạy, độ phủ JaCoCo vào cuối file này.
- [ ] Liệt kê các test đang hỏng hoặc bị `@Disabled`. Sửa, hoặc ghi lý do.
- [ ] CI (`.github/workflows/ci-cd.yml`) chạy `verify` cho mọi PR.

**Xong khi:** `verify` xanh trên CI và đã có số liệu ban đầu.

## PR-02 · Test bao phủ cho các luồng chính (golden path)
Các luồng sẽ bị refactor nhiều nhất phải có test tích hợp trước. Với mỗi luồng, kiểm tra đã có `*IT` chưa; chưa có thì viết.
- [ ] Đặt ngay: giữ chỗ → trả payOS (mock) → check-in → START → COMPLETED.
- [ ] Đặt trước: giữ chỗ → trả bằng ví + payOS → CONFIRMED.
- [ ] Hết giữ chỗ → EXPIRED; tiền về muộn → `PAYMENT_REVIEW_REQUIRED` + `LATE_PAYMENT_CREDIT`.
- [ ] Hủy trước T−60 hoàn 100%; hủy sau T−60 theo tỷ lệ vi phạm.
- [ ] NO_SHOW: thường, và khi máy lỗi (hoàn 100%).
- [ ] Giặt - sấy: chuyển máy sấy, hết hạn chuyển (`TransferExpiryJob`).
- [ ] Kiosk: giữ chỗ → checkout → start → yêu cầu hoàn.
- [ ] Mua thêm phút sấy: thành công, và bo từ chối thì hoàn ví.
- [ ] Locker giặt hộ: gửi đồ → cân → trả theo kg → lấy đồ.

**Xong khi:** mỗi dòng trên trỏ được tới một test có tên cụ thể (ghi tên test cạnh ô đã tick).

## PR-03 · Ảnh chụp schema
- [ ] Chạy `scripts/generate-database-sql.sh` để tạo `database.sql` snapshot từ schema hiện tại, rồi commit.
- [ ] Dùng snapshot này để so sánh trước và sau mỗi task của GĐ1.

## PR-04 · Sao lưu DB thật
- [ ] Tạo `pg_dump` của DB production hoặc DB đang chạy tại cửa hàng. Lưu ngoài repo.
- [ ] Viết vào `docs/` cách khôi phục bản sao lưu lên một Postgres local để chạy thử migration (dry-run).

## PR-05 · Quy ước code thành văn bản
Viết `docs/CONVENTIONS.md`, gồm:
- [ ] Cấu trúc module: `controller / service / domain / repository / dto / entity / event` (theo ADR-001).
- [ ] Truy cập dữ liệu: SQL chỉ nằm trong `repository`. Chọn một kiểu cho mỗi bảng: JPA cho CRUD, `JdbcTemplate` hoặc `JdbcClient` cho truy vấn phức tạp.
- [ ] Tiền: luôn dùng `Money`, lưu `BIGINT` cột `*_vnd`. Thời gian: luôn `Instant` + `Clock` tiêm vào, lưu `TIMESTAMPTZ`.
- [ ] DTO: dùng `record`, tách `request/` và `response/`.
- [ ] Đặt tên ràng buộc DB: `pk_`, `fk_<bảng>_<cột>`, `uq_`, `chk_`, `idx_`.

## PR-06 · Kiểm tra kiến trúc tự động
- [ ] Thêm ArchUnit test: `domain` không import Spring/JPA/JDBC; `controller` không import `repository`; `shared` không phụ thuộc module.
- [ ] Thêm luật cấm `Instant.now()` / `OffsetDateTime.now()` / `LocalDateTime.now()` ngoài `ClockConfig`. Tạm thời ghi danh sách vi phạm hiện có vào allowlist, rồi giảm dần ở GĐ2–GĐ4 (V-09).

**Xong khi:** ArchUnit chạy trong `verify`, và allowlist được ghi rõ số file.

---

### Số liệu ban đầu (điền ở PR-01)

| Chỉ số | Giá trị |
|---|---|
| Số test unit / IT | |
| Thời gian `verify` | |
| Độ phủ dòng (JaCoCo) | |
| Số file vi phạm `now()` | 37 (đếm ngày 23/11/2026) |
| Số file dùng `BigDecimal` / `Money` | 66 / 25 |
