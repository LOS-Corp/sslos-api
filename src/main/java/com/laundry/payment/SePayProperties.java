package com.laundry.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cổng thanh toán SePay (Payment Gateway, https://developer.sepay.vn/vi/cong-thanh-toan/gioi-thieu): khách được chuyển
 * sang trang thanh toán của SePay (chuyển khoản / thẻ), SePay báo kết quả qua IPN; backend cũng hỏi trạng thái đơn.
 *
 * @param env           sandbox (mặc định) hoặc production; mỗi môi trường có cặp Merchant ID / Secret Key riêng
 * @param merchantId    Merchant ID của đơn vị bán hàng (dạng SP-TEST-XXXXXXX ở sandbox)
 * @param secretKey     Secret Key (spsk_test_... / spsk_live_...): ký form thanh toán và gọi API (Basic auth)
 * @param ipnSecretKey  khóa đặt ở Cổng thanh toán → Cấu hình → IPN (xác thực SECRET_KEY); SePay gửi header X-Secret-Key
 * @param publicBaseUrl địa chỉ backend mà trình duyệt / điện thoại của khách mở được, để vào trang chuyển sang SePay
 *                      (mặc định http://localhost:8080; kiosk cần địa chỉ LAN, ví dụ http://192.168.1.4:8080)
 * @param successUrl    SePay đưa khách về đây khi trả xong
 * @param errorUrl      khi thanh toán lỗi
 * @param cancelUrl     khi khách hủy
 */
@ConfigurationProperties(prefix = "app.sepay")
public record SePayProperties(String env, String merchantId, String secretKey, String ipnSecretKey, String publicBaseUrl,
                              String successUrl, String errorUrl, String cancelUrl) {

    public boolean configured() {
        return notBlank(merchantId) && notBlank(secretKey);
    }

    public boolean production() {
        return "production".equalsIgnoreCase(env == null ? "" : env.trim());
    }

    /** Nơi trình duyệt gửi form thanh toán. */
    public String checkoutUrl() {
        return production() ? "https://pay.sepay.vn/v1/checkout/init" : "https://pay-sandbox.sepay.vn/v1/checkout/init";
    }

    /** API của cổng (tra cứu / hủy đơn). */
    public String apiBaseUrl() {
        return production() ? "https://pgapi.sepay.vn" : "https://pgapi-sandbox.sepay.vn";
    }

    public String publicBase() {
        return (notBlank(publicBaseUrl) ? publicBaseUrl.trim() : "http://localhost:8080").replaceAll("/+$", "");
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
