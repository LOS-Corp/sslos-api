# GĐ6 · Phần còn lại và tài liệu

---

## OT-01 · Gộp trùng lặp (V-12)
- [ ] Hai class `Role` (`shared.security.Role` và `user.entity.Role`): giữ entity cho bảng `roles`, enum quyền ở `shared.security`, và đặt tên khác nhau cho rõ.
- [ ] Gộp `shared.exception` vào `shared.error`. Các exception của module kế thừa `BusinessException` (ADR-006).

## OT-02 · DTO một kiểu
- [ ] Áp quy ước PR-05 (`record`, `dto/request`, `dto/response`) cho `booking`, `locker`, `wallet`, `iot`, `machine`.
- [ ] Không đổi JSON. Snapshot test hoặc OpenAPI diff phải giống trước khi đổi.

## OT-03 · Tách `LockerService` (603 dòng)
- [ ] Tách theo use case: gửi đồ (PIN, BR-17), xử lý (cân, thu theo kg), lấy đồ (BR-18), phí lưu.
- [ ] SQL về repository, dùng `Clock`.

## OT-04 · `auth`, `user`, `audit`, `attachment`
- [ ] Rà `AuthService` (236): JWT 15 phút, refresh 7 ngày xoay vòng, khóa đăng nhập 5 lần sai trong 15 phút. Giữ nguyên hành vi, dùng `Clock`.
- [ ] `OtpCode`, `RefreshToken`, `User`: thay `now()` trong entity bằng giá trị truyền vào từ service.
- [ ] `audit`: kiểm tra mọi lệnh nhạy cảm (BR-25) đều ghi audit. Liệt kê các `AuditAction` không còn được dùng.

## OT-05 · Package `kiosk` và `config`
- [ ] `kiosk` chia `controller / service / dto` như các module khác.
- [ ] `config` chỉ giữ cấu hình hạ tầng (Jackson, WebMvc, Clock). Tham số nghiệp vụ về module của nó (xem BK-08).

## OT-06 · README và ma trận phân quyền (V-13)
- [ ] Viết lại `README.md`: topic MQTT đúng, sơ đồ trạng thái đúng (lấy từ MA-02), danh sách lệnh đúng (lấy từ MA-04).
- [ ] Khôi phục ma trận phân quyền thành `docs/permissions.md`: vai trò × endpoint, gồm cả KIOSK.
- [ ] Sửa các tham chiếu tới `task/09_PHAN_QUYEN_4_VAI_TRO.md` trong `SecurityConfig.java`, `Roles.java`, `V41` (chỉ sửa comment, không sửa migration), `ADR-003`, `README.md`.
- [ ] `SecurityMatrixIT` kiểm tra theo đúng file mới.

## OT-07 · Nhật ký thay đổi API cho frontend
Ghi mọi thay đổi ảnh hưởng `sslos-customer-web`, `sslos-management-web`, `sslos-kiosk`:

| Task | Endpoint / trường | Thay đổi | Frontend cần sửa |
|---|---|---|---|
| MA-05 | `/api/machines/{code}/load...` | Xóa | customer-web, kiosk |
| KO-03 | `GET /api/bookings/{id}/check-in-qr` | Mới | customer-web |
| KO-04/05 | `/api/kiosk/check-ins...` | Mới | kiosk |
| | | | |

- [ ] Cập nhật bảng trong mỗi PR có thay đổi API.
- [ ] Ghi ADR cho các quyết định lớn: BK-02 (giới hạn START), PM-03 (một đơn nhiều khoản thu), GĐ5 (cọc).
