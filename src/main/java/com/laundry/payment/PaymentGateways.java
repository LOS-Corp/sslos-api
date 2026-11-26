package com.laundry.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Các cổng thanh toán của cửa hàng: cổng nào có đủ khóa trong .env thì bật, khách chọn được trên web / kiosk.
 * app.payment.gateway (payos | sepay) là cổng mặc định khi tạo phiếu. Không cổng nào có khóa, hoặc bật
 * PAYMENT_SIMULATION_ENABLED, thì chạy giả lập (QR giả, xác nhận bằng POST /api/dev/payments/{id}/simulate).
 */
@Component
public class PaymentGateways {

    /** Kết quả tạo phiếu ở cổng: link trang thanh toán và (payOS) chuỗi VietQR. */
    public record Created(PaymentIntent.Gateway gateway, String checkoutUrl, String qrCode, String paymentLinkId) {
    }

    private final PayOsGateway payos;
    private final SePayGateway sepay;
    private final PaymentIntent.Gateway preferred;
    private final boolean simulationEnabled;

    public PaymentGateways(PayOsGateway payos, SePayGateway sepay,
                           @Value("${app.payment.gateway:payos}") String gateway,
                           @Value("${app.payment.simulation-enabled:false}") boolean simulationEnabled) {
        this.payos = payos;
        this.sepay = sepay;
        this.preferred = "sepay".equals(gateway == null ? "" : gateway.trim().toLowerCase(Locale.ROOT))
                ? PaymentIntent.Gateway.SEPAY : PaymentIntent.Gateway.PAYOS;
        this.simulationEnabled = simulationEnabled;
    }

    private boolean configured(PaymentIntent.Gateway g) {
        return g == PaymentIntent.Gateway.SEPAY ? sepay.configured() : payos.configured();
    }

    /** Cổng khách chọn được: cổng có khóa; không cổng nào có khóa thì chỉ cổng mặc định (giả lập). */
    public List<PaymentIntent.Gateway> available() {
        List<PaymentIntent.Gateway> out = new ArrayList<>();
        // Cổng mặc định đứng đầu danh sách.
        for (PaymentIntent.Gateway g : preferred == PaymentIntent.Gateway.SEPAY
                ? List.of(PaymentIntent.Gateway.SEPAY, PaymentIntent.Gateway.PAYOS)
                : List.of(PaymentIntent.Gateway.PAYOS, PaymentIntent.Gateway.SEPAY)) {
            if (configured(g)) out.add(g);
        }
        return out.isEmpty() ? List.of(preferred) : out;
    }

    /** Cổng dùng khi tạo phiếu: cổng mặc định nếu đã bật, không thì cổng đầu tiên đang bật. */
    public PaymentIntent.Gateway active() {
        List<PaymentIntent.Gateway> list = available();
        return list.contains(preferred) ? preferred : list.get(0);
    }

    /** Cổng {@code g} chạy giả lập (chưa có khóa / bật giả lập). */
    public boolean simulated(PaymentIntent.Gateway g) {
        return simulationEnabled || !configured(g == null ? PaymentIntent.Gateway.PAYOS : g);
    }

    /** Cổng mặc định chạy giả lập. */
    public boolean simulated() {
        return simulated(active());
    }

    public Created create(long code, long amount, String description, Instant expiresAt) {
        return create(active(), code, amount, description, expiresAt);
    }

    /**
     * Tạo phần thanh toán ở cổng {@code g}. SePay không trả link / QR qua API: checkoutUrl là trang của backend tự gửi
     * form có chữ ký sang SePay; không có chuỗi VietQR (kiosk hiện QR của link để khách mở bằng điện thoại).
     */
    public Created create(PaymentIntent.Gateway g, long code, long amount, String description, Instant expiresAt) {
        if (simulated(g)) {
            String name = g.name().toLowerCase(Locale.ROOT);
            return new Created(g, "sim://" + name + "/" + code, "SIMULATED|" + code + "|" + amount, null);
        }
        if (g == PaymentIntent.Gateway.SEPAY) {
            // Link trang thanh toán công khai của SePay (mở được trên điện thoại); lỗi thì dùng trang nội bộ của backend.
            String url = sepay.initCheckout(code, amount, description);
            return new Created(g, url != null ? url : sepay.checkoutLink(code), null, null);
        }
        PayOsGateway.Link link = payos.create(code, amount, description, expiresAt);
        return new Created(g, link.checkoutUrl(), link.qrCode(), link.paymentLinkId());
    }

    /** Hủy phần thanh toán ở cổng khi phiếu hết hạn / bị thay; lỗi chỉ ghi log. */
    public void cancel(PaymentIntent intent, String reason) {
        cancel(intent.getGateway(), intent.getPayosOrderCode(), reason);
    }

    public void cancel(PaymentIntent.Gateway g, Long code, String reason) {
        if (code == null || simulated(g)) return;
        if (g == PaymentIntent.Gateway.SEPAY) sepay.cancel(code);
        else payos.cancel(code, reason);
    }

    /** Phương thức ghi trên biên nhận cho tiền về của phiếu. */
    public static Payment.Method methodOf(PaymentIntent intent) {
        return intent.getGateway() == PaymentIntent.Gateway.SEPAY ? Payment.Method.SEPAY : Payment.Method.PAYOS;
    }
}
