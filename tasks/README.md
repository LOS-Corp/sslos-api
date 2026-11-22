# Kế hoạch refactor backend + database — sslos-api

Thư mục này gồm hai phần:

1. **Tài liệu nghiệp vụ.** Hệ thống đang làm gì và phải làm gì. Đây là "hợp đồng" cần giữ nguyên khi refactor.
2. **Danh sách task.** Chia theo giai đoạn. Xong việc nào thì đổi `- [ ]` thành `- [x]`.

## Mục lục

| File | Nội dung |
|---|---|
| [00_NGHIEP_VU.md](00_NGHIEP_VU.md) | **Nghiệp vụ mục tiêu duy nhất** (không nói về code/DB): vai trò, dịch vụ, giá, đặt trước / đặt ngay, kiosk check-in và kích hoạt máy, thời gian, hủy, sự cố, giặt hộ, thanh toán, hoàn tiền, ví, tham số |
| [01_HIEN_TRANG_VA_VAN_DE.md](01_HIEN_TRANG_VA_VAN_DE.md) | Hiện trạng code + DB và các vấn đề đã tìm thấy (có dẫn chứng file/dòng) |
| [02_QUYET_DINH_CAN_CHOT.md](02_QUYET_DINH_CAN_CHOT.md) | Những câu hỏi nghiệp vụ phải trả lời **trước** khi code |
| [10_GD0_CHUAN_BI.md](10_GD0_CHUAN_BI.md) | Giai đoạn 0: lưới an toàn (test, sao lưu DB, quy ước) |
| [11_GD1_DATABASE.md](11_GD1_DATABASE.md) | Giai đoạn 1: làm sạch schema (tiền, thời gian, bảng/cột thừa, baseline) |
| [12_GD2_DAT_CHO.md](12_GD2_DAT_CHO.md) | Giai đoạn 2: module đặt chỗ (booking) |
| [13_GD3_THANH_TOAN_VI_HOAN_TIEN.md](13_GD3_THANH_TOAN_VI_HOAN_TIEN.md) | Giai đoạn 3: thanh toán, ví, hoàn tiền |
| [14_GD4_MAY_VA_IOT.md](14_GD4_MAY_VA_IOT.md) | Giai đoạn 4: máy, quyền START, IoT/MQTT |
| [15_GD5_KIOSK_VA_DAT_COC.md](15_GD5_KIOSK_VA_DAT_COC.md) | Giai đoạn 5: nghiệp vụ mới (cọc 20%, quét QR đơn trên kiosk, trả nốt 80%) |
| [16_GD6_CON_LAI_VA_TAI_LIEU.md](16_GD6_CON_LAI_VA_TAI_LIEU.md) | Giai đoạn 6: auth/user, locker, audit, shared, tài liệu |

## Thứ tự làm

```
GĐ0 chuẩn bị ─> chốt 02_QUYET_DINH ─> GĐ1 database ─> GĐ2 đặt chỗ ─> GĐ3 tiền ─> GĐ4 máy/IoT ─> GĐ5 kiosk + cọc ─> GĐ6
                                          (GĐ2, GĐ3, GĐ4 có thể làm song song sau GĐ1, nhưng GĐ5 cần cả ba xong)
```

## Quy ước cho mọi task

- **Mỗi task là một PR nhỏ.** Không trộn refactor với đổi nghiệp vụ trong cùng PR. Task refactor không được làm đổi hành vi.
- **Trước khi đóng task:** `./mvnw verify` phải xanh (cần Docker cho Testcontainers). Đánh dấu `[x]` vào đây trong cùng PR.
- **Schema chỉ đổi qua Flyway.** Không sửa migration đã chạy trên production. Mỗi thay đổi là một file `V<n>__...sql` mới.
- **Migration có xóa dữ liệu:** chạy thử trên bản sao DB thật trước. Đánh dấu ô "Đã chạy thử trên bản sao" trong task.
- **Mã task** dạng `DB-03`, `BK-05`... dùng làm tiền tố tên nhánh và commit: `refactor(BK-05): ...`.
- Một task bị bỏ hoặc đổi hướng: gạch ngang `~~...~~` và ghi lý do. Không xóa task.

## Tiến độ tổng

Cập nhật bảng này khi đóng một giai đoạn.

| Giai đoạn | Số task | Xong |
|---|---|---|
| GĐ0 Chuẩn bị | 6 | 0 |
| Quyết định nghiệp vụ | 10 | 0 |
| GĐ1 Database | 9 | 0 |
| GĐ2 Đặt chỗ | 9 | 0 |
| GĐ3 Thanh toán, ví, hoàn tiền | 7 | 0 |
| GĐ4 Máy, IoT | 7 | 0 |
| GĐ5 Kiosk + cọc | 9 | 0 |
| GĐ6 Còn lại + tài liệu | 7 | 0 |
