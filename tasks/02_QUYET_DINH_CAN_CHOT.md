# 02 · Quyết định cần chốt trước khi code

> **Lưu ý (23/11/2026):** các câu hỏi dưới đây đã có câu trả lời trong bản nghiệp vụ mới, [00_NGHIEP_VU.md](00_NGHIEP_VU.md). Ví dụ: Q-01 nằm ở mục 6; Q-02 và Q-03 ở mục 11 (mất 20% tiền giữ chỗ, bỏ cách hoàn 100/70/50/0%); Q-05 ở mục 15; Q-09 ở mục 12. Những gì còn chờ chủ cửa hàng xác nhận nằm ở **mục 19 của 00**. Riêng Q-06 (xóa code cân) và Q-08 (gộp migration) là quyết định kỹ thuật, vẫn chốt ở file này.

Mỗi câu hỏi có một đề xuất. Chốt xong thì đánh dấu `[x]`, ghi câu trả lời vào dòng **Chốt:** và ngày chốt. Những task phụ thuộc một câu hỏi ghi rõ mã `Q-xx`.

---

- [ ] **Q-01 · Cửa sổ check-in**
  - Đề xuất: mở từ T−15 (chỉ khi máy rảnh, như hiện nay), đóng lúc **T + `check_in_minutes`** của cửa hàng. Bỏ hằng số `checkInClosesAfter`.
  - Với đơn đặt ngay: đóng lúc max(T, lúc trả tiền) + `check_in_minutes`.
  - Chốt: ……  (ngày ……)

- [ ] **Q-02 · Khách đặt trước không đến thì cọc xử lý thế nào**
  - Phương án A: mất toàn bộ cọc 20% và ghi 1 vi phạm.
  - Phương án B: áp `RefundPolicy` lên phần cọc (lần 1 hoàn 100%, lần 2 70%, lần 3 50%, từ lần 4 0%).
  - Đề xuất: **A**. Cọc là để giữ chỗ; nếu lần đầu vẫn hoàn 100% thì cọc mất ý nghĩa.
  - Chốt: ……

- [ ] **Q-03 · Khách hủy đơn đã cọc**
  - Đề xuất: hủy trước hoặc đúng T−60 thì hoàn 100% cọc vào ví. Sau T−60 thì xử lý như không đến (theo Q-02).
  - Chốt: ……

- [ ] **Q-04 · Tên gọi và điều khoản của khoản 20%**
  - Nếu gọi là "đặt cọc", Điều 328 BLDS 2015 quy định bên nhận cọc vi phạm phải trả lại cọc **cộng thêm một khoản tương đương**, trừ khi có thỏa thuận khác.
  - Đề xuất: gọi là **"tiền giữ chỗ (trả trước 20%)"**, và ghi rõ trong điều khoản: "máy lỗi hoặc cửa hàng không phục vụ được thì hoàn 100% số đã trả". Nên hỏi lại người có chuyên môn pháp lý.
  - Chốt: ……

- [ ] **Q-05 · Hoàn tiền cho khách vãng lai (kiosk)**
  - Hiện tại (V72): tạo yêu cầu hoàn, nhân viên trả tận tay tại quầy.
  - Phương án mới: chi hộ payOS (Payouts) về đúng tài khoản đã chuyển tiền, lấy từ webhook. Cần đăng ký dịch vụ chi hộ và nạp sẵn số dư.
  - Đề xuất: giữ cách hiện tại trong đợt refactor. Chi hộ payOS làm thành task riêng sau (`KO-09`).
  - Chốt: ……

- [ ] **Q-06 · Xóa tính năng cân của khâu tự phục vụ (V-05)**
  - Đề xuất: **xóa** code và cột/bảng liên quan. Giữ `booking_weight_charges` vì giặt hộ vẫn cân.
  - Chốt: ……

- [ ] **Q-07 · Ví có dùng được cho cọc và phần 80% không**
  - Đề xuất: được dùng ví cho cọc 20% (trên app/web, đã đăng nhập). Phần 80% trên kiosk chỉ trả qua payOS, vì kiosk không biết danh tính khách.
  - Chốt: ……

- [ ] **Q-08 · Gộp migration thành baseline**
  - Câu hỏi: DB production đã có dữ liệu thật chưa?
    - Chưa có: có thể gộp V1–V72 thành một `V1__baseline.sql` sạch.
    - Đã có: giữ nguyên lịch sử. Chỉ thêm migration dọn dẹp, và có thể dùng `baselineVersion` cho các môi trường mới.
  - Chốt: ……

- [ ] **Q-09 · Máy được gán bị lỗi lúc khách đến**
  - Đề xuất: tự chuyển sang máy **cùng loại, có profile giá bằng hoặc thấp hơn**, đang rảnh trong khoảng đã giữ. Nếu không có máy nào thì hoàn 100% (BR-13).
  - Chốt: ……

- [ ] **Q-10 · Hạn của QR thanh toán trên kiosk**
  - Đề xuất: bằng thời gian giữ chỗ (300 giây, BR-04). Hết hạn thì hủy link payOS và nhả máy. Tiền về sau đó thì tự hoàn (vào ví, hoặc tạo yêu cầu hoàn với khách vãng lai).
  - Chốt: ……
