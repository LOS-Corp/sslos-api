# 00 · Nghiệp vụ LaundryOS

Đây là **toàn bộ nghiệp vụ mục tiêu** của cửa hàng: hệ thống sau khi refactor phải chạy đúng như tài liệu này. Tài liệu không nói về code hay database. Chỗ nào code hiện tại làm khác, xem [01_HIEN_TRANG_VA_VAN_DE.md](01_HIEN_TRANG_VA_VAN_DE.md) và các file task.

Quy ước:
- **T** = giờ bắt đầu của lượt đặt.
- **(*)** = quy tắc lấy theo thông lệ của tiệm giặt tự phục vụ / giặt hộ, **chưa được chủ cửa hàng xác nhận**. Danh sách các quy tắc này ở mục 19.
- Mọi con số trong tài liệu là **giá trị mặc định**. Bảng tổng hợp ở mục 18 ghi rõ con số nào chủ cửa hàng chỉnh được.

---

## 1. Mô hình cửa hàng

- **Một cửa hàng**, gồm:
  - máy giặt và máy sấy tự phục vụ, mỗi máy có số hiệu, sức chứa (kg) và giá riêng;
  - tủ locker cho dịch vụ giặt hộ;
  - **một kiosk** (màn hình cảm ứng có camera quét QR) đặt tại cửa hàng.
- Khách dùng **app/web** để đặt trước, trả tiền, xem lịch sử và ví.
- **Kiosk là nơi duy nhất để check-in và kích hoạt máy**, cho cả đơn đặt trước lẫn đơn đặt ngay. Khách không quét QR trên máy giặt.
- **Màn hình trên máy** chỉ hiện số máy, trạng thái, hướng dẫn "Check-in tại kiosk" và thời gian còn lại khi đang chạy. Nút START trên máy chỉ có tác dụng sau khi kiosk đã kích hoạt máy cho một đơn.

## 2. Vai trò

| Vai trò | Là ai | Làm gì |
|---|---|---|
| **Khách có tài khoản** | Người dùng app/web | Đặt trước, trả cọc hoặc trả đủ, dùng ví, hủy/đổi giờ, đặt giặt hộ, xem lịch sử, rút ví |
| **Khách vãng lai** | Người đến cửa hàng, không có tài khoản | Đặt ngay trên kiosk và trả 100%. Có thể để lại số điện thoại (tùy chọn) để nhận thông báo và hoàn tiền |
| **Kiosk** | Thiết bị tại cửa hàng | (1) Bán lượt đặt ngay cho khách vãng lai. (2) Quét QR đơn đặt trước, thu phần còn lại. (3) **Kích hoạt máy cho mọi đơn.** (4) Bán thêm phút sấy |
| **Nhân viên** | Người trực cửa hàng | Hỗ trợ khách, xử lý đồ bỏ quên, nhận và trả đồ giặt hộ, ra lệnh cho máy (bắt buộc có lý do), kích hoạt máy thủ công khi kiosk lỗi, xử lý hoàn tiền trong hạn mức (mục 15) |
| **Chủ cửa hàng** | Người sở hữu | Cấu hình cửa hàng (giờ mở cửa, giá, tham số), quản lý máy và lịch bảo trì, duyệt hoàn tiền vượt hạn mức nhân viên, xử lý rút ví, xem báo cáo |
| **Quản trị hệ thống** | Kỹ thuật | Tài khoản, thiết bị IoT, danh mục máy. Không can thiệp vào tiền của khách |

Mọi thao tác nhạy cảm (ra lệnh máy, hoàn tiền ngoại lệ, kích hoạt thủ công, đổi giá) đều phải ghi **lý do** và lưu vào **nhật ký không sửa được**.

## 3. Dịch vụ

| Dịch vụ | Mô tả |
|---|---|
| **Giặt** | 1 máy giặt, 1 lượt |
| **Sấy** | 1 máy sấy, 1 lượt. Mua thêm phút được trong lúc đang sấy |
| **Giặt + sấy** | 1 máy giặt, sau đó 1 máy sấy, cả hai gán ngay lúc đặt |
| **Giặt hộ** | Khách gửi đồ vào locker, nhân viên giặt, sấy, gấp (và ủi nếu khách chọn), rồi trả đồ vào locker |

**Không có ủi tự phục vụ.** Ủi là dịch vụ thêm của giặt hộ.

## 4. Giá

### 4.1 Tự phục vụ
- **Giá theo từng máy.** Mỗi máy có giá một lượt và thời gian một lượt, chủ cửa hàng cài đặt. Máy lớn thường đắt hơn. Máy chưa có giá thì không bán.
- **Giặt + sấy** = giá máy giặt + giá máy sấy đã chọn. Khi cần hoàn một chặng, giá trị của chặng đó bằng đúng giá của máy đó.
- **Sấy thêm:** bán theo gói, ví dụ +10 phút. Giá gói do chủ cửa hàng cài cho từng máy sấy.
- Giá lượt giặt **đã gồm bột giặt và nước xả** nếu máy có châm tự động. Nếu không, cửa hàng bán gói bột giặt tại quầy (*).
- Khách chọn máy theo sức chứa. App và kiosk ghi rõ sức chứa và gợi ý máy phù hợp với khối lượng khách khai. Hệ thống không cân đồ tự phục vụ.

### 4.2 Giặt hộ
- Tính **theo kg**, làm tròn **lên** đến 0,5 kg; tối thiểu **3 kg** (*).
- Đồ đặc biệt (chăn, mền, rèm, gấu bông, đồ da, đồ len) **tính theo món**, theo bảng giá riêng (*).
- Dịch vụ thêm: ủi (tính theo món) (*); giặt nhanh 6 giờ, phụ thu 50% (*).

### 4.3 Làm tròn
- Tiền tính bằng đồng, không có số lẻ.
- Tiền cọc làm tròn **lên** đến bội số 1.000 đồng (*). Phần còn lại = tổng tiền − tiền cọc.

## 5. Kênh đặt và loại đơn

Loại đơn được xác định theo **nơi đặt**, không theo khoảng cách thời gian.

| | **Đơn đặt trước** | **Đơn đặt ngay** |
|---|---|---|
| Đặt ở đâu | App / web | Kiosk tại cửa hàng |
| Ai đặt | Khách có tài khoản | Bất kỳ ai (thường là khách vãng lai) |
| Giờ bắt đầu | Khách chọn, từ bây giờ đến tối đa 48 giờ tới | Lúc máy rảnh sớm nhất (ngay, hoặc chờ tối đa 30 phút) |
| Trả lúc đặt | **Tối thiểu 20%** (tiền giữ chỗ). Được chọn trả 100% | **100%** |
| Phần còn lại | Trả trên app bất cứ lúc nào trước khi check-in, hoặc trả tại kiosk khi check-in | Không có |
| Máy | Gán cố định lúc đặt | Gán lúc mua |
| Check-in / kích hoạt | Quét QR đơn tại kiosk | Kích hoạt ngay sau khi trả tiền trên kiosk |

**Hạn mức đặt trước:** trong mỗi khung giờ, đơn đặt trước chỉ được chiếm tối đa **70%** số máy của mỗi loại (làm tròn xuống, tối thiểu 1 máy). Phần còn lại luôn dành cho khách đến tại chỗ. Ví dụ: 4 máy giặt thì tối đa 2 máy cho đặt trước, vì 70% × 4 = 2,8, làm tròn xuống được 2.

## 6. Thời gian của một lượt

### 6.1 Các khoảng thời gian

| Khoảng | Mặc định | Ý nghĩa |
|---|---|---|
| Giữ chỗ để thanh toán | 5 phút | Trong lúc khách quét QR trả tiền, máy được giữ cho khách |
| Check-in sớm | 15 phút trước T | Đến sớm được check-in **nếu máy đang rảnh** |
| **Ân hạn** | **10 phút sau T** | Mốc chót để check-in **và trả đủ tiền** tại kiosk. Quá mốc này thì đơn là "không đến" |
| Thời hạn bấm START | 3 phút | Tính từ lúc kiosk kích hoạt máy |
| Thời gian chạy | Theo từng máy | Thời gian một lượt của máy |
| Lấy đồ | 10 phút | Sau khi máy chạy xong |
| Dọn máy | 5 phút | Nhân viên hoặc khách lau cửa, lồng, khay bột |
| Chuyển đồ (Giặt + sấy) | 10 phút | Từ lúc máy giặt xong đến lúc bấm START máy sấy |

### 6.2 Khoảng giữ máy

Mỗi lượt giữ máy trong một khoảng **liền mạch**, và hai lượt **không bao giờ chồng nhau** trên cùng một máy.

```
Đặt trước:  T ─[ân hạn 10']─[bấm START 3']─[chạy]─[lấy đồ 10']─[dọn 5']─> lượt sau được bắt đầu
Đặt ngay:   lúc mua ─[thanh toán ≤5']─ T = lúc trả xong ─[ân hạn 10']─[bấm START 3']─[chạy]─[lấy đồ 10']─[dọn 5']─>
```

- Đơn đặt ngay phải **chờ máy** (máy rảnh lúc HH:MM) thì T = HH:MM.
- Mọi đơn chỉ được kích hoạt (và kích hoạt lại) **trong ân hạn**. Sau lần kích hoạt cuối, khách vẫn có 3 phút để bấm START. Như vậy máy chạy muộn nhất lúc T + 13 phút, nằm trong khoảng đã giữ.

- Khoảng giữ máy đã tính sẵn trường hợp khách đến muộn nhất. Khách đến đúng giờ thì máy xong sớm hơn. Kiosk được bán phần thời gian trống đó cho khách đặt ngay, miễn là lượt mới xong trước giờ T của lượt kế tiếp.
- Khách check-in sớm thì máy chạy sớm, nhưng lượt **không** được kéo dài thêm.

### 6.3 Giờ mở cửa
- Chỉ đặt và bán lượt trong giờ mở cửa.
- Một lượt phải **xong cả phần lấy đồ trước giờ đóng cửa**. Kiosk và app tự ngừng bán khi không còn kịp.
- Chủ cửa hàng đặt **lịch bảo trì** cho từng máy. Trong khoảng bảo trì, máy không nhận đặt.

## 7. Đặt trước trên app / web

1. Khách chọn dịch vụ, giờ bắt đầu, và máy. App chỉ hiện máy còn trống trong cả khoảng giữ máy và còn trong hạn mức 70%.
2. Hệ thống **giữ chỗ 5 phút** để khách thanh toán.
3. Khách trả **20% hoặc 100%**, bằng ví và/hoặc QR payOS. Trả trong 5 phút thì đơn được **xác nhận**. Quá 5 phút thì chỗ được nhả. Nếu tiền về sau đó, hệ thống tự hoàn, không giữ lại chỗ.
4. Đơn xác nhận có **QR đơn** và **mã đơn 6 ký tự**.
   - QR chỉ chứa một mã ngẫu nhiên, không chứa thông tin cá nhân.
   - QR quét được **nhiều lần** trong thời gian check-in, nhưng **tiền chỉ thu một lần**.
   - Đơn xong, hủy hoặc không đến thì QR hết hiệu lực.
5. Trước khi đến, khách có thể **trả nốt phần còn lại trên app**, để khi tới kiosk chỉ cần quét QR.
6. Khách nhận nhắc nhở: trước T 30 phút, đúng giờ T, và khi ân hạn còn 5 phút.

## 8. Kiosk: check-in và kích hoạt máy

Kiosk có 3 nút chính: **Quét QR đơn** · **Giặt / sấy ngay** · **Mua thêm phút sấy**.

### 8.1 Đơn đặt trước: quét QR đơn
1. Khách đưa QR đơn vào camera của kiosk. Mất điện thoại thì nhập **mã đơn + 4 số cuối số điện thoại** (*).
2. Kiosk kiểm tra:

   | Tình huống | Kiosk xử lý |
   |---|---|
   | Còn quá 15 phút mới tới T | "Chưa đến giờ, mời quay lại lúc HH:MM" |
   | Trong 15 phút trước T, máy đang bận | Báo giờ dự kiến máy rảnh. Nếu có máy cùng loại, cùng giá đang rảnh thì mời chuyển sang máy đó (*) |
   | Quá mốc T + 10 phút | "Đã hết thời gian check-in" (xem mục 11) |
   | Máy được gán bị lỗi / mất kết nối / bảo trì | Xử lý theo mục 12 |
   | Hợp lệ | Sang bước 3 |

3. **Còn phải trả:** kiosk hiện số tiền và QR payOS. QR hết hạn sau 5 phút, nhưng không được quá mốc ân hạn (T + 10 phút). **Đã trả đủ:** bỏ qua bước này.
4. Tiền về đủ thì kiosk **kích hoạt máy** và hiện "Mời ra **máy số X** bấm START trong 3 phút".
5. Khách bấm START trên máy, máy chạy.

### 8.2 Đơn đặt ngay: Giặt / sấy ngay
1. Khách chọn dịch vụ, rồi chọn máy. Kiosk chỉ hiện máy online, không lỗi, và lượt mới sẽ xong trước giờ T của lượt đặt trước kế tiếp trên máy đó. Mỗi máy ghi rõ "rảnh ngay" hoặc "rảnh lúc HH:MM" (chờ tối đa 30 phút).
2. Khách nhập số điện thoại nếu muốn (tùy chọn), để nhận thông báo và hoàn tiền nhanh.
3. Kiosk giữ máy 5 phút và hiện QR payOS cho **100%**.
4. Tiền về thì kiosk hiện **biên nhận có QR và mã đơn**, khách chụp lại hoặc nhận qua số điện thoại. Sau đó:
   - **Máy rảnh ngay:** kiosk kích hoạt luôn, khách có 3 phút bấm START.
   - **Máy rảnh lúc HH:MM:** đơn có giờ T = HH:MM. Đến giờ, khách quét QR biên nhận tại kiosk để kích hoạt, với ân hạn 10 phút như đơn đặt trước.

### 8.3 Đã kích hoạt mà chưa bấm START kịp
- Quá 3 phút mà máy chưa chạy: kích hoạt hết hiệu lực. Khách **quét lại QR** (đơn hoặc biên nhận) để kích hoạt lại, **không trả thêm tiền**.
- Được kích hoạt lại đến hết ân hạn (T + 10 phút). Với đơn đặt ngay mà máy rảnh ngay, T là lúc trả tiền xong.
- Quá mốc đó, khách gặp nhân viên. Nhân viên có thể chuyển khách sang máy đang trống, hoặc xử lý như hủy muộn (mục 11).

### 8.4 Mua thêm phút sấy
- Trên kiosk (quét QR đơn hoặc biên nhận) hoặc trên app, khi máy sấy **đang chạy**.
- Chỉ bán khi phần thêm không chồng lên lượt kế tiếp. Tối đa **thêm 60 phút** mỗi lượt.
- Trả xong mà máy không nhận lệnh thêm phút: hoàn 100% gói đó.

## 9. Trong lúc máy chạy

- **Máy giặt chỉ báo "đang chạy" khi máy thật sự chạy.** Kiosk kích hoạt chưa đủ để coi là đang chạy.
- **Mất mạng** lúc đang chạy: máy vẫn chạy hết chu kỳ. Hệ thống ghi nhận lại khi có mạng.
- **Khách tự dừng máy** giữa chừng: lượt coi như đã dùng, không hoàn tiền.
- **Giặt + sấy:** khi máy giặt xong, máy sấy được **tự kích hoạt** cho đơn, khách không phải quay lại kiosk. Khách có **10 phút** để chuyển đồ và bấm START máy sấy.
  - Quá 10 phút: nếu máy sấy không vướng lượt sau, được giữ thêm 10 phút (*).
  - Hết thời gian giữ thêm mà chưa bấm START: chặng sấy bị hủy, hoàn 80% giá chặng sấy (mục 11).
- Khách nhận thông báo khi máy còn 5 phút và khi máy xong.

## 10. Sau khi máy xong

### 10.1 Lấy đồ
- Khách có **10 phút** lấy đồ ra.
- Quá 10 phút và máy đã có lượt kế tiếp:
  - Có nhân viên: nhân viên lấy đồ ra, cho vào túi ghi mã đơn, cất ở quầy.
  - Không có nhân viên: khách sau được lấy đồ ra, cho vào **giỏ chờ** của máy đó. Khu vực này có camera (*).
- Cửa hàng không chịu trách nhiệm về đồ để quá hạn trong máy hoặc trong giỏ chờ (*). Điều này phải ghi trong điều khoản.

### 10.2 Đồ bỏ quên
- Nhân viên ghi nhận, chụp ảnh, cất kèm mã đơn.
- Giữ **miễn phí 24 giờ**. Sau đó tính phí lưu theo ngày (*).
- Quá **30 ngày**: cửa hàng thanh lý hoặc từ thiện. Trước đó phải báo khách 2 lần nếu có liên hệ (*).

## 11. Hủy, đổi giờ, không đến

### 11.1 Đơn đặt trước

| Tình huống | Xử lý |
|---|---|
| Hủy **trước T − 60 phút**, hoặc trong **10 phút sau khi đặt** (và vẫn trước T) | Hoàn **100%** số đã trả |
| Hủy muộn (sau các mốc trên, trước khi hết ân hạn) | **Mất tiền giữ chỗ (20% giá đơn)**. Phần đã trả thêm được hoàn 100% |
| **Không đến** (hết ân hạn mà chưa check-in và trả đủ) | Như hủy muộn. Máy được trả ngay cho khách khác |
| Đổi giờ **trước T − 60 phút** | Miễn phí **1 lần** nếu còn chỗ. Tiền đã trả chuyển sang giờ mới |
| Đổi giờ sau T − 60 phút | Không được đổi. Khách hủy muộn rồi đặt lại |

- Quy tắc "10 phút sau khi đặt" dành cho người đặt nhầm. Nó cũng giải quyết trường hợp đơn đặt sát giờ: khi đặt đã quá mốc T − 60 nên không có cơ hội hủy miễn phí.
- **Lặp lại nhiều lần:** 3 lần không đến hoặc hủy muộn trong 90 ngày thì trong 30 ngày tiếp theo, khách phải **trả 100%** khi đặt trước (*). Không đến vẫn chỉ mất 20%.
- Khoản 20% gọi là **"tiền giữ chỗ"**. Điều khoản ghi rõ: cửa hàng không phục vụ được thì hoàn 100% số đã trả, không bồi thường thêm. Nên nhờ người có chuyên môn pháp lý xem lại điều khoản này.

### 11.2 Đơn đặt ngay

| Tình huống | Xử lý |
|---|---|
| Hủy trước khi trả tiền | Miễn phí, nhả máy ngay |
| Đã trả, hủy trên kiosk trước khi bấm START (trong thời hạn 3 phút) | Hoàn **100%** |
| Đã trả nhưng bỏ không dùng (hết ân hạn mà máy chưa chạy) | Mất 20%, hoàn **80%** |

## 12. Sự cố từ phía cửa hàng

Lỗi do cửa hàng thì **khách không bị mất tiền** và không bị tính là vi phạm.

| Sự cố | Xử lý |
|---|---|
| Máy được gán bị lỗi, mất kết nối hoặc bảo trì trước giờ khách dùng | Tự chuyển sang máy **cùng loại, sức chứa không nhỏ hơn**, đang rảnh trong khoảng giữ máy, và báo cho khách. Máy mới rẻ hơn thì hoàn phần chênh. Không có máy thay thì hoàn **100%** |
| Máy hỏng giữa chừng | Nhân viên giúp lấy đồ ra. Khách chọn: **chạy lại miễn phí** trên máy khác cùng loại, hoặc **hoàn 100%** lượt đó |
| Giặt + sấy: máy sấy hỏng sau khi giặt xong | Chuyển máy sấy khác, hoặc hoàn 100% giá chặng sấy |
| Kiosk lỗi | Nhân viên kích hoạt máy thủ công (ghi lý do). Ngoài giờ có nhân viên thì đơn bị ảnh hưởng được hoàn 100% |
| Mất điện toàn cửa hàng | Mọi lượt bị ảnh hưởng: chạy lại miễn phí khi có điện, hoặc hoàn 100% |
| Nhân viên dừng máy của khách | Bắt buộc ghi lý do. Do cửa hàng (máy kêu lạ, rò nước...) thì hoàn 100% phần chưa dùng |

## 13. Giặt hộ (qua locker)

1. **Đặt:** khách chọn giờ gửi đồ và dịch vụ thêm (ủi, giặt nhanh) trên app. Khách trả **phí tối thiểu** (giá 3 kg) và nhận mã mở locker.
2. **Gửi đồ:** khách mở ô locker và bỏ đồ vào, trong khoảng từ T đến **T + 60 phút**. Quá hạn mà không gửi: mất 20% phí tối thiểu, hoàn 80%.
3. **Nhận và kiểm đồ:** nhân viên làm các việc sau:
   - lấy đồ ra, kiểm túi áo quần;
   - chụp ảnh vết bẩn hoặc hư hỏng có sẵn;
   - tách đồ ra màu và đồ cần giặt riêng;
   - **cân** và báo giá cuối cùng cho khách trên app.

   Món không nhận giặt (đồ da, đồ cần giặt khô...) thì báo khách và trả lại cùng đồ đã giặt (*).
4. **Thanh toán phần chênh:** giá cuối lớn hơn phí tối thiểu thì khách trả phần chênh trên app (ví hoặc payOS) **trước khi lấy đồ**.
5. **Thời gian trả đồ:** tiêu chuẩn **24 giờ**, giặt nhanh **6 giờ** (*). Xong thì báo khách kèm mã mở locker.
6. **Lấy đồ:** mở locker bằng mã, sau khi đã trả đủ.
   - Lưu miễn phí **48 giờ** sau khi xong. Quá hạn tính phí lưu theo ngày (*).
   - Quá 30 ngày thì xử lý như đồ bỏ quên (mục 10.2).
7. **Trách nhiệm:**
   - Cửa hàng không chịu trách nhiệm về đồ để trong túi; phai màu, co rút do chất liệu; và các hư hỏng đã ghi nhận lúc nhận đồ.
   - Đồ bị hỏng hoặc mất do lỗi cửa hàng: bồi thường tối đa **10 lần giá giặt** của món đó (*).
   - Khiếu nại trong vòng **24 giờ** sau khi lấy đồ (*).

## 14. Thanh toán

- **Phương thức:**
  - **QR payOS** (chuyển khoản ngân hàng) trên app và trên kiosk.
  - **Ví** chỉ dùng trên app, cho khách có tài khoản. Hệ thống trừ ví trước, phần thiếu trả qua payOS.
  - Không thu tiền mặt qua hệ thống (*).
- **QR có số tiền cố định và có hạn.** Tiền về đúng hạn và đúng số mới được ghi nhận. Tiền về sau hạn, hoặc sai số tiền: không ghi nhận cho đơn, tự hoàn lại (mục 15).
- **Mỗi khoản chỉ thu một lần.** Thông báo thanh toán bị gửi lặp lại thì không được cộng tiền hai lần.
- **Biên nhận:** mỗi khoản thu có biên nhận điện tử kèm mã đơn. Khách có tài khoản xem trong app. Khách vãng lai nhận trên màn hình kiosk, hoặc qua số điện thoại nếu đã nhập.

## 15. Hoàn tiền và ví

### 15.1 Hoàn tiền
- **Tổng tiền hoàn của một đơn không bao giờ vượt số khách đã trả.**
- **Hoàn về đâu:**
  - Khách có tài khoản: **vào ví ngay**. Khách có thể rút ví về ngân hàng (mục 15.2).
  - Khách vãng lai: **tự động chuyển về tài khoản đã chuyển tiền**, trong vòng 1 ngày làm việc (*). Không xác định được tài khoản thì khách đến quầy, đưa biên nhận và số điện thoại.
- **Ai duyệt:**
  - Hoàn theo đúng quy tắc trong tài liệu này: tự động, không cần duyệt.
  - Hoàn ngoại lệ (ngoài quy tắc, ví dụ khách khiếu nại): nhân viên tạo, kèm lý do. **Đến 100.000 đ** nhân viên tự duyệt; **trên 100.000 đ** chủ cửa hàng duyệt (*).
- Mọi khoản hoàn đều ghi nhật ký: ai, lúc nào, bao nhiêu, vì sao.

### 15.2 Ví
- Chỉ khách có tài khoản mới có ví. Tiền vào ví từ hai nguồn: tiền hoàn, và tiền về muộn của đơn đã nhả chỗ.
- Hiện **chưa có nạp ví trực tiếp** và chưa có khuyến mãi nạp ví (*).
- Số dư không bao giờ âm và không chuyển được cho người khác.
- **Rút ví:**
  1. Khách gửi yêu cầu kèm số tài khoản ngân hàng. Số tiền bị giữ ngay.
  2. Chủ cửa hàng chuyển khoản trong **3 ngày làm việc** và đính kèm chứng từ thì mới hoàn tất.
  3. Bị từ chối thì số tiền giữ trả lại ví.
- Mỗi ngày hệ thống đối soát số dư ví với lịch sử giao dịch. Lệch thì báo chủ cửa hàng.

## 16. Vận hành của nhân viên và chủ cửa hàng

- **Trạng thái máy** (hiện trên web quản lý, kiosk và màn hình máy): Sẵn sàng · Đã giữ cho đơn · Chờ bấm START · Đang chạy · Xong, chờ lấy đồ · Lỗi · Bảo trì · Mất kết nối.
- **Lệnh cho máy** (dừng, khởi động lại, kiểm tra): chỉ nhân viên trở lên được ra lệnh, bắt buộc ghi lý do.
- **Máy hỏng:** nhân viên chuyển máy sang "Lỗi" hoặc "Bảo trì". Các đơn đã gán cho máy đó được xử lý ngay theo mục 12.
- **Cửa hàng chỉ nhận đặt khi đã sẵn sàng:** có giờ mở cửa, có ít nhất một dịch vụ bật, có máy đã có giá, và có ít nhất một máy online.
- **Đổi cấu hình** (giá, giờ mở cửa, tham số): không ảnh hưởng đơn đã xác nhận. Đơn cũ giữ giá và giờ cũ. Thay đổi làm đơn đã xác nhận không còn chạy được (ví dụ đóng cửa sớm hơn giờ một lượt đã đặt) thì bị từ chối, cho đến khi chủ cửa hàng tự xử lý các đơn đó.

## 17. Thông báo cho khách

| Thời điểm | Đơn đặt trước | Đơn đặt ngay (nếu có số điện thoại) |
|---|---|---|
| Đặt / thanh toán thành công | ✓ | ✓ |
| Trước T 30 phút, đúng T | ✓ | — |
| Ân hạn còn 5 phút | ✓ | ✓ (nếu đang chờ máy) |
| Máy được chuyển sang máy khác | ✓ | ✓ |
| Máy còn 5 phút / đã xong | ✓ | ✓ |
| Quá hạn lấy đồ | ✓ | ✓ |
| Hoàn tiền | ✓ | ✓ |
| Giặt hộ: báo giá sau khi cân, đồ đã xong | ✓ | — |

## 18. Bảng tham số mặc định

| Tham số | Mặc định | Chủ cửa hàng chỉnh được |
|---|---|---|
| Giữ chỗ để thanh toán | 5 phút | Không |
| Đặt trước xa nhất | 48 giờ | Có |
| Check-in sớm | 15 phút trước T | Có |
| **Ân hạn** | **10 phút sau T** | Có |
| Thời hạn bấm START sau khi kích hoạt | 3 phút | Có |
| Lấy đồ | 10 phút | Có |
| Dọn máy | 5 phút | Có |
| Chuyển đồ (Giặt + sấy) | 10 phút (+10 phút nếu máy sấy rảnh) | Có |
| Tiền giữ chỗ đặt trước | 20%, làm tròn lên 1.000 đ | Có |
| Hủy miễn phí | Trước T − 60 phút, hoặc 10 phút sau khi đặt | Có |
| Mất khi hủy muộn / không đến | 20% giá đơn | Theo tiền giữ chỗ |
| Hạn mức đặt trước | 70% số máy mỗi loại | Có |
| Kiosk chờ máy tối đa | 30 phút | Có |
| Sấy thêm tối đa | 60 phút / lượt | Có |
| Vi phạm lặp lại | 3 lần / 90 ngày → trả 100% trong 30 ngày | Có |
| Đồ bỏ quên | Miễn phí 24 giờ, thanh lý sau 30 ngày | Có |
| Giặt hộ: gửi đồ | Đến T + 60 phút | Có |
| Giặt hộ: tối thiểu / làm tròn | 3 kg / 0,5 kg | Có |
| Giặt hộ: trả đồ | 24 giờ (nhanh 6 giờ, +50%) | Có |
| Giặt hộ: lưu miễn phí | 48 giờ | Có |
| Giặt hộ: bồi thường tối đa | 10 lần giá giặt món đó | Có |
| Hoàn ngoại lệ nhân viên tự duyệt | Đến 100.000 đ | Có |

## 19. Quy tắc theo thông lệ, cần chủ cửa hàng xác nhận

Các quy tắc có dấu (*) ở trên. Đây là cách làm phổ biến của tiệm giặt tự phục vụ và giặt hộ, tôi dùng để lấp chỗ trống hoặc thay chỗ phi lý. Chủ cửa hàng duyệt từng dòng:

- [ ] Giá giặt đã gồm bột giặt và nước xả (nếu máy có châm tự động) (4.1)
- [ ] Giặt hộ: tối thiểu 3 kg, làm tròn 0,5 kg, đồ đặc biệt tính theo món, ủi theo món, giặt nhanh +50% (4.2)
- [ ] Tiền giữ chỗ làm tròn lên 1.000 đ (4.3)
- [ ] Mất điện thoại: check-in bằng mã đơn + 4 số cuối số điện thoại (8.1)
- [ ] Đến sớm mà máy bận: mời chuyển sang máy cùng loại, cùng giá đang rảnh (8.1)
- [ ] Giặt + sấy: giữ máy sấy thêm 10 phút nếu rảnh (9)
- [ ] Không có nhân viên: khách sau được lấy đồ quá hạn ra giỏ chờ có camera; cửa hàng không chịu trách nhiệm đồ quá hạn (10.1)
- [ ] Đồ bỏ quên: phí lưu theo ngày sau 24 giờ, thanh lý sau 30 ngày (10.2)
- [ ] 3 lần vi phạm trong 90 ngày thì phải trả 100% khi đặt trước trong 30 ngày (11.1)
- [ ] Giặt hộ: trả lại món không nhận giặt; thời gian trả đồ 24 giờ / 6 giờ; lưu miễn phí 48 giờ; bồi thường tối đa 10 lần; khiếu nại trong 24 giờ (13)
- [ ] Không thu tiền mặt qua hệ thống (14)
- [ ] Hoàn tiền cho khách vãng lai về tài khoản đã chuyển trong 1 ngày làm việc (15.1)
- [ ] Hạn mức nhân viên tự duyệt hoàn ngoại lệ: 100.000 đ (15.1)
- [ ] Chưa có nạp ví trực tiếp (15.2)

## 20. Những điểm đã sửa so với bản trước

| Bản trước | Bản này | Lý do |
|---|---|---|
| Khách quét QR trên máy để kích hoạt; kiosk chỉ cho khách vãng lai | **Kiosk kích hoạt máy cho mọi đơn** | Theo yêu cầu: một điểm check-in duy nhất |
| Phân loại dùng ngay / đặt trước theo ngưỡng 15 phút, trong khi kiosk cho đặt xa 30 phút | Phân loại **theo nơi đặt** (kiosk / app) | Khách ở kiosk luôn có mặt tại cửa hàng, không cần cọc |
| Lịch giữ 5 phút check-in, nhưng QR quét được đến T + 10 | **Một con số ân hạn**, nằm sẵn trong khoảng giữ máy, cộng thêm thời hạn bấm START | Máy không bao giờ chạy lấn sang lượt sau |
| QR đơn "dùng một lần" nhưng lại "quét lại để kích hoạt lại" | QR quét **nhiều lần**, tiền thu **một lần** | Hai câu cũ mâu thuẫn nhau |
| Giữ máy trong lúc trả tiền, áp cho cả đơn đặt trước | Chỉ cần với đơn đặt ngay | Đơn đặt trước đã có máy cố định |
| QR trả 80% hết hạn theo thời gian giữ chỗ 5 phút | Hết hạn sau 5 phút **nhưng không quá mốc ân hạn**. Có thể trả 80% trước trên app | Đơn đã xác nhận, không còn ở bước giữ chỗ |
| Hủy miễn phí đến T − 60, áp cả cho đơn đặt sát giờ | Thêm **10 phút sau khi đặt** được hủy miễn phí | Đơn đặt sát giờ trước đây không bao giờ hủy miễn phí được |
| Không đến lần đầu hoàn 100%, lần 2 70%, lần 3 50%, sau đó 0% | **Mất 20% tiền giữ chỗ**, cố định. Vi phạm lặp lại thì phải trả 100% khi đặt trước | Cách cũ làm tiền giữ chỗ mất ý nghĩa |
| Giá trị chặng combo = giá combo × giá lẻ / tổng giá lẻ | Giá trị chặng = **giá của máy đó** | Giá combo đã là tổng giá hai máy |
| Có dịch vụ ủi tự phục vụ nhưng lại cấm ủi tự phục vụ | Ủi chỉ là dịch vụ thêm của giặt hộ | Bỏ mâu thuẫn |
| Khách vãng lai "nhận tiền hoàn tại quầy" mà không có cách nhận diện | Tự hoàn về tài khoản đã chuyển. Dự phòng: biên nhận + số điện thoại | Có cách định danh người nhận |
| Chủ cửa hàng duyệt hoàn, nhân viên xử lý hoàn, không rõ ranh giới | Tự động theo quy tắc; ngoại lệ phân theo hạn mức 100.000 đ | Rõ ai chịu trách nhiệm |
| Chuyển máy rẻ hơn khi máy gán bị lỗi, không nói phần chênh | Hoàn phần chênh | Khách không trả cho thứ không dùng |
| Không có quy tắc lấy đồ quá hạn, đồ bỏ quên, giờ đóng cửa, trách nhiệm giặt hộ | Đã bổ sung (mục 6.3, 10, 13) | Tình huống hằng ngày của tiệm giặt |
