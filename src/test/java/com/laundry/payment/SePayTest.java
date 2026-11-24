package com.laundry.payment;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Form thanh toán của Cổng thanh toán SePay: thứ tự trường, chữ ký, mã đơn. Không cần DB / mạng. */
class SePayTest {

    private final SePayProperties props = new SePayProperties("sandbox", "SP-TEST-ABC123", "spsk_test_secret", "ipn",
            "http://192.168.1.4:8080", "http://localhost:5173/payment/return", null, "http://localhost:5173/payment/cancel");
    private final SePayGateway sepay = new SePayGateway(props, JsonMapper.builder().build());

    private static String hmac(String data, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void formIsSignedInTheDocumentedOrderSkippingEmptyFields() throws Exception {
        Map<String, String> form = sepay.checkoutForm(1234567, 30000, "Thanh toan don FSLCFB");
        // Thứ tự theo tài liệu SePay; error_url không đặt nên bỏ qua.
        String signed = "order_amount=30000,merchant=SP-TEST-ABC123,currency=VND,operation=PURCHASE,"
                + "order_description=Thanh toan don FSLCFB,order_invoice_number=SSLOS1234567,"
                + "success_url=http://localhost:5173/payment/return,cancel_url=http://localhost:5173/payment/cancel";
        assertThat(form.get("signature")).isEqualTo(hmac(signed, "spsk_test_secret"));
        assertThat(form).doesNotContainKey("error_url");
        assertThat(form.keySet()).startsWith("order_amount", "merchant", "currency", "operation");
    }

    @Test
    void invoiceNumberMapsBackToTheIntentCode() {
        assertThat(SePayGateway.invoice(1234567)).isEqualTo("SSLOS1234567");
        assertThat(SePayGateway.codeOf("SSLOS1234567")).isEqualTo(1234567L);
        assertThat(SePayGateway.codeOf("INV-1759134677")).isNull();
        assertThat(SePayGateway.codeOf(null)).isNull();
    }

    @Test
    void sandboxAndProductionUseTheirOwnHosts() {
        assertThat(props.checkoutUrl()).isEqualTo("https://pay-sandbox.sepay.vn/v1/checkout/init");
        assertThat(props.apiBaseUrl()).isEqualTo("https://pgapi-sandbox.sepay.vn");
        SePayProperties live = new SePayProperties("production", "m", "s", null, null, null, null, null);
        assertThat(live.checkoutUrl()).isEqualTo("https://pay.sepay.vn/v1/checkout/init");
        assertThat(sepay.checkoutLink(42)).isEqualTo("http://192.168.1.4:8080/api/payments/sepay/checkout/42");
    }
}
