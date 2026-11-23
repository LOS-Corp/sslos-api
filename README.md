# SSLOS API (LaundryOS backend)

Backend cho cửa hàng giặt tự phục vụ + giặt hộ: đặt lượt trên app, kiosk check-in và kích hoạt máy, thanh toán payOS + ví, hoàn tiền, bo mạch IoT qua MQTT. Nghiệp vụ: `00_NGHIEP_VU.md` (số `§x` trong code là mục của tài liệu đó).

Java 21 · Spring Boot 4.1 · PostgreSQL 17 + Flyway · EMQX 5.8 · Jackson 3 · Paho MQTT.

## Chạy

```bash
cp .env.example .env          # điền secret
docker compose up -d --build  # postgres + emqx + backend
```

Chạy backend ngoài docker (postgres + emqx vẫn trong docker): `./run-be.ps1` (đọc `.env`).

| Dịch vụ | Cổng | Ghi chú |
|---|---|---|
| Backend | 8080 | Swagger: `/swagger-ui.html` |
| EMQX MQTT | 1883 (chỉ localhost), 8883 TLS (LAN cho bo mạch) | Xác thực bo bằng bảng `mqtt_accounts` |
| EMQX dashboard | 18083 (chỉ localhost) | |
| PostgreSQL | 5432 (chỉ localhost) | |

Chưa có khóa payOS: đặt `PAYMENT_SIMULATION_ENABLED=true`, rồi xác nhận tiền về bằng `POST /api/dev/payments/{intentId}/simulate`.

## Cấu trúc

```
com.laundry
├── common        lỗi (ErrorCode, ApiError), tiền, token, AssignedIdEntity, AfterCommit
├── security      JWT người dùng + X-Kiosk-Key, phân quyền theo đường dẫn
├── audit         nhật ký không sửa được (§2)
├── auth, user    đăng ký, đăng nhập, refresh xoay vòng, quên/đổi mật khẩu, quản trị tài khoản
├── store         tham số §18, giờ mở cửa / có nhân viên, ngày nghỉ, "sẵn sàng nhận đặt" (§16)
├── machine       máy, giá theo máy, gói sấy thêm, Lỗi/Bảo trì, lịch bảo trì, trạng thái hiển thị
├── device        bo mạch: đăng ký có chữ ký, duyệt khóa, gắn máy/tủ, MQTT hub, lệnh xuống bo
├── order         đặt trước / đặt ngay, lịch trống + hạn mức 70%, kiosk check-in, kích hoạt, vòng START,
│                 combo giặt + sấy, hủy / đổi giờ / không đến, sự cố cửa hàng, sấy thêm, job thời gian
├── payment       phiếu thu, payOS + webhook chống lặp, giả lập
├── refund        hoàn theo quy tắc / ngoại lệ có hạn mức duyệt
├── wallet        ví, sổ cái chỉ thêm, rút ví, đối soát hằng đêm
├── notification  thông báo hẹn giờ (§17)
├── attachment    ảnh / chứng từ
└── locker, kiosk tủ locker, kiosk
```

## Phân quyền

| Đường dẫn | Ai |
|---|---|
| `/api/public/**`, `/api/auth/(register\|login\|refresh\|forgot-password\|reset-password)` | ai cũng gọi được |
| `/api/devices/register/**` | bo mạch tự đăng ký |
| `/api/payments/payos/webhook` | payOS |
| `/api/kiosk/**` | kiosk (`X-Kiosk-Key`, tạo ở `POST /api/admin/kiosks`) |
| `/api/staff/**` | nhân viên, chủ cửa hàng |
| `/api/owner/**` | chủ cửa hàng |
| `/api/admin/**` | quản trị hệ thống |
| `/api/**` khác | người dùng đã đăng nhập (`/api/me`, `/api/orders`, `/api/wallet`, `/api/notifications`) |

## Luồng chính

**Đặt trước (app):** `GET /api/public/availability/slots` → `POST /api/orders` (giữ chỗ 5') → `POST /api/orders/{id}/payments` (`DEPOSIT` 20% hoặc `FULL`, `useWallet`) → đơn `CONFIRMED`, app hiện QR (`qrToken`).

**Kiosk:** `POST /api/kiosk/scan {qrToken}` → `PAYMENT_REQUIRED` (QR trả nốt) hoặc `ACTIVATED` ("Mời ra máy số X bấm START trong 3 phút"). Đặt ngay: `GET /api/kiosk/machines` → `POST /api/kiosk/walk-in` → trả 100% → máy rảnh ngay thì tự kích hoạt.

**Máy:** `AUTHORIZE_START` (authorizationId = commandId) → khách bấm START → bo gửi `START_REQUEST` → backend `START_APPROVAL` trong 5 s → `STARTED` → đơn đang chạy. Bo báo `COMPLETED` → chờ lấy đồ 10'; combo thì máy sấy tự kích hoạt, khách có 10' chuyển đồ (+10' nếu máy sấy rảnh).

**Job (15 s):** nhả giữ chỗ hết hạn, phiếu thu hết hạn, không đến (giữ 20%, hoàn phần còn lại), hết hạn bấm START, hết thời gian lấy đồ, chuyển lượt khỏi máy mất kết nối.

## Thiết bị

1. Bo cài Wi-Fi, gọi `POST /api/devices/register/challenge` + `/api/devices/register`, nhận mật khẩu MQTT niêm phong bằng khóa của nó.
2. Admin so dấu vân tay trên màn bo: `POST /api/admin/devices/{id}/approve {fingerprint}`.
3. Gắn máy: `POST /api/admin/devices/{id}/bind {machineId}` (hoặc `lockerId`).

Chứng chỉ TLS của broker sinh một lần vào volume `sslos_emqx_certs`; bo ghim SHA-256 ở lần nối đầu, **đừng xóa volume này**.

## Test

```bash
./mvnw test                                   # unit + khởi động context trên PostgreSQL trống (SPRING_DATASOURCE_URL)
LIVE_E2E=true E2E_API=http://localhost:8080 ./mvnw test -Dtest=LiveFlowTest
```

`LiveFlowTest` chạy trên backend + EMQX thật với bo mạch giả (`FakeBoard`, đúng giao thức firmware): đăng ký thiết bị, đặt giặt + sấy, trả 20% rồi trả nốt ở kiosk, vòng START, máy sấy tự kích hoạt, mua thêm phút sấy, đặt ngay + hủy hoàn tiền, ví, hạn mức đặt trước. Backend cần `PAYMENT_SIMULATION_ENABLED=true`.

## Chưa làm

Giặt hộ qua locker (vé QR mở ô tại kiosk), đồ bỏ quên, báo cáo. Schema đã có sẵn (`laundry_jobs`, `locker_assignments`, `found_items`...).
