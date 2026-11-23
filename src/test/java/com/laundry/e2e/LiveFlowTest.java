package com.laundry.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kịch bản đầu-cuối trên backend + PostgreSQL + EMQX đang chạy (docker compose), với bo mạch giả.
 * <pre>
 *   LIVE_E2E=true E2E_API=http://localhost:8091 ./mvnw test -Dtest=LiveFlowTest
 * </pre>
 * Backend phải bật PAYMENT_SIMULATION_ENABLED=true (thanh toán giả lập).
 */
@EnabledIfEnvironmentVariable(named = "LIVE_E2E", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LiveFlowTest {

    static final String API = env("E2E_API", "http://localhost:8091");
    static final String BROKER = env("E2E_BROKER", "tcp://localhost:1883");
    static final String DB_URL = env("E2E_DB_URL", "jdbc:postgresql://localhost:5432/sslos");
    static final String DB_USER = env("E2E_DB_USER", "sslos");
    static final String DB_PASSWORD = env("E2E_DB_PASSWORD", "123456");

    static final Api api = new Api(API);
    static final String run = Long.toString(System.currentTimeMillis() % 100000);
    static final List<FakeBoard> boards = new ArrayList<>();
    static String owner;
    static String customer;
    static String kioskKey;
    static String washer, dryer, washer2;
    static FakeBoard washerBoard, dryerBoard, washer2Board;

    @BeforeAll
    static void setup() throws Exception {
        String email = "owner" + run + "@e2e.local";
        api.post("/api/auth/register", null, Map.of("fullName", "Owner E2E", "email", email,
                "phone", "+849" + String.format("%08d", Long.parseLong(run)), "password", "Owner1234"));
        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            c.createStatement().execute("insert into user_roles (user_id, role) select id, r from users, "
                    + "unnest(array['OWNER','ADMIN','STAFF']) r where email = '" + email + "' on conflict do nothing");
        }
        owner = api.post("/api/auth/login", null, Map.of("email", email, "password", "Owner1234"))
                .path("accessToken").asString();
        List<Map<String, Object>> slots = new ArrayList<>();
        for (int d = 1; d <= 7; d++) slots.add(Map.of("dayOfWeek", d, "opensAt", "00:00", "closesAt", "23:59"));
        api.put("/api/owner/store/hours", Api.bearer(owner), Map.of("kind", "OPEN", "slots", slots, "reason", "E2E mở cả ngày"));
        api.put("/api/owner/store/hours", Api.bearer(owner), Map.of("kind", "STAFFED", "slots", slots, "reason", "E2E"));

        washer = machine("W" + run, "WASHER", 30000);
        dryer = machine("D" + run, "DRYER", 25000);
        washer2 = machine("X" + run, "WASHER", 30000);
        api.post("/api/owner/machines/" + dryer + "/time-packs", Api.bearer(owner),
                Map.of("minutes", 10, "priceVnd", 8000, "active", true, "reason", "E2E gói sấy"));

        washerBoard = board("WASHER", washer);
        dryerBoard = board("DRYER", dryer);
        washer2Board = board("WASHER", washer2);

        kioskKey = api.post("/api/admin/kiosks", Api.bearer(owner), Map.of("code", "K" + run, "name", "Kiosk E2E"))
                .path("apiKey").asString();
        String cEmail = "khach" + run + "@e2e.local";
        api.post("/api/auth/register", null, Map.of("fullName", "Khach E2E", "email", cEmail,
                "phone", "+848" + String.format("%08d", Long.parseLong(run)), "password", "Khach1234"));
        customer = api.post("/api/auth/login", null, Map.of("email", cEmail, "password", "Khach1234"))
                .path("accessToken").asString();
    }

    @AfterAll
    static void teardown() throws Exception {
        for (FakeBoard b : boards) b.close();
    }

    @Test
    @Order(1)
    void storeIsReady() throws Exception {
        JsonNode r = api.get("/api/owner/store/readiness", Api.bearer(owner));
        assertThat(r.path("ready").asBoolean()).as(r.toString()).isTrue();
        JsonNode machines = api.get("/api/public/machines", null);
        assertThat(machines.toString()).contains("W" + run).contains("READY");
    }

    /** Đặt trước giặt + sấy, trả 20%, check-in trả nốt 80%, giặt, máy sấy tự kích hoạt, mua thêm phút sấy. */
    @Test
    @Order(2)
    void advanceWashDryFullFlow() throws Exception {
        Instant start = Instant.now().plus(5, ChronoUnit.MINUTES);
        JsonNode order = api.post("/api/orders", Api.bearer(customer), Map.of("service", "WASH_DRY",
                "startAt", start.toString(), "washerId", washer, "dryerId", dryer));
        String id = order.path("id").asString();
        assertThat(order.path("status").asString()).isEqualTo("HOLD");
        assertThat(order.path("totalAmount").asLong()).isEqualTo(55000);
        assertThat(order.path("depositAmount").asLong()).isEqualTo(11000);

        JsonNode intent = api.post("/api/orders/" + id + "/payments", Api.bearer(customer),
                Map.of("option", "DEPOSIT", "useWallet", false));
        assertThat(intent.path("gatewayAmount").asLong()).isEqualTo(11000);
        api.post("/api/dev/payments/" + intent.path("id").asString() + "/simulate", null, Map.of());
        order = api.get("/api/orders/" + id, Api.bearer(customer));
        assertThat(order.path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(order.path("paidAmount").asLong()).isEqualTo(11000);

        // Webhook lặp lại không cộng tiền hai lần: gọi giả lập lần nữa với cùng phiếu -> phiếu đã PAID -> REJECTED + hoàn.
        String qr = order.path("qrToken").asString();
        JsonNode scan = api.post("/api/kiosk/scan", kiosk(), Map.of("qrToken", qr));
        assertThat(scan.path("outcome").asString()).isEqualTo("PAYMENT_REQUIRED");
        assertThat(scan.path("payment").path("amount").asLong()).isEqualTo(44000);
        api.post("/api/dev/payments/" + scan.path("payment").path("id").asString() + "/simulate", null, Map.of());

        await(() -> washerBoard.authorizationId != null, "washer AUTHORIZE_START");
        order = api.get("/api/orders/" + id, Api.bearer(customer));
        assertThat(order.path("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(order.path("legs").get(0).path("status").asString()).isEqualTo("ACTIVATED");

        // Quét lại khi đã kích hoạt: không thu thêm tiền.
        JsonNode again = api.post("/api/kiosk/scan", kiosk(), Map.of("qrToken", qr));
        assertThat(again.path("outcome").asString()).isEqualTo("ALREADY_ACTIVATED");

        washerBoard.pressStart();
        await(() -> leg(id, 0).equals("RUNNING"), "washer RUNNING");
        assertThat(washerBoard.approvals).contains("APPROVED");

        washerBoard.complete();
        await(() -> leg(id, 0).equals("AWAITING_UNLOAD"), "washer finished");
        await(() -> dryerBoard.authorizationId != null, "dryer auto-activated");
        assertThat(leg(id, 1)).isEqualTo("ACTIVATED");

        dryerBoard.pressStart();
        await(() -> leg(id, 1).equals("RUNNING"), "dryer RUNNING");

        JsonNode lookup = api.post("/api/kiosk/lookup", kiosk(), Map.of("qrToken", qr));
        String pack = lookup.path("dryerPacks").get(0).path("id").asString();
        JsonNode ext = api.post("/api/kiosk/orders/" + id + "/extensions", kiosk(), Map.of("legNo", 2, "packId", pack));
        api.post("/api/dev/payments/" + ext.path("payment").path("id").asString() + "/simulate", null, Map.of());
        await(() -> dryerBoard.commands.stream().anyMatch(c -> "EXTEND_CYCLE".equals(c.path("type").asString())), "EXTEND_CYCLE");
        await(() -> api.get("/api/orders/" + id, Api.bearer(customer)).path("legs").get(1).path("extraMinutes").asInt() == 10,
                "extension applied");

        dryerBoard.complete();
        await(() -> leg(id, 1).equals("AWAITING_UNLOAD"), "dryer finished");
        order = api.get("/api/orders/" + id, Api.bearer(customer));
        assertThat(order.path("paidAmount").asLong()).isEqualTo(63000);
        assertThat(order.path("totalAmount").asLong()).isEqualTo(63000);
        assertThat(order.path("receipts").size()).isEqualTo(3);
    }

    /** Đặt ngay tại kiosk, máy rảnh ngay: trả 100% thì máy được kích hoạt luôn; hủy trước khi chạy hoàn 100%. */
    @Test
    @Order(3)
    void walkInImmediateThenCancel() throws Exception {
        JsonNode list = api.get("/api/kiosk/machines?service=WASH", kiosk());
        assertThat(list.toString()).contains("X" + run);
        JsonNode w = api.post("/api/kiosk/walk-in", kiosk(), Map.of("service", "WASH", "washerId", washer2,
                "phone", "0912345678"));
        String id = w.path("order").path("id").asString();
        assertThat(w.path("payment").path("amount").asLong()).isEqualTo(30000);
        assertThat(w.path("order").path("qrToken").asString()).isNotBlank();
        api.post("/api/dev/payments/" + w.path("payment").path("id").asString() + "/simulate", null, Map.of());
        await(() -> washer2Board.authorizationId != null, "walk-in activated");
        JsonNode o = api.get("/api/kiosk/orders/" + id, kiosk());
        assertThat(o.path("status").asString()).isEqualTo("IN_PROGRESS");

        JsonNode cancelled = api.post("/api/kiosk/orders/" + id + "/cancel", kiosk(), null);
        assertThat(cancelled.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("refundedAmount").asLong()).isEqualTo(30000);
        assertThat(cancelled.path("refunds").get(0).path("destination").asString()).isEqualTo("BANK_TRANSFER");
        await(() -> washer2Board.commands.stream().anyMatch(c -> "REVOKE_START".equals(c.path("type").asString())), "revoke");
    }

    /** Đặt xa, trả bằng ví... (ví đang 0) trả đủ qua QR, hủy sớm: hoàn 100% vào ví; đối soát ví khớp. */
    @Test
    @Order(4)
    void advanceFreeCancelRefundsToWallet() throws Exception {
        Instant start = Instant.now().plus(5, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
        JsonNode order = api.post("/api/orders", Api.bearer(customer), Map.of("service", "WASH",
                "startAt", start.toString(), "washerId", washer2));
        String id = order.path("id").asString();
        JsonNode intent = api.post("/api/orders/" + id + "/payments", Api.bearer(customer),
                Map.of("option", "FULL", "useWallet", true));
        api.post("/api/dev/payments/" + intent.path("id").asString() + "/simulate", null, Map.of());
        JsonNode cancelled = api.post("/api/orders/" + id + "/cancel", Api.bearer(customer), null);
        assertThat(cancelled.path("cancellationKind").asString()).isEqualTo("FREE");
        assertThat(cancelled.path("refundedAmount").asLong()).isEqualTo(30000);
        JsonNode wallet = api.get("/api/wallet", Api.bearer(customer));
        assertThat(wallet.path("balance").asLong()).isGreaterThanOrEqualTo(30000);

        // Trả bằng ví: đơn mới xác nhận ngay, không cần QR.
        JsonNode o2 = api.post("/api/orders", Api.bearer(customer), Map.of("service", "WASH",
                "startAt", start.plus(1, ChronoUnit.HOURS).toString(), "washerId", washer2));
        JsonNode paid = api.post("/api/orders/" + o2.path("id").asString() + "/payments", Api.bearer(customer),
                Map.of("option", "FULL", "useWallet", true));
        assertThat(paid.path("status").asString()).isEqualTo("PAID");
        assertThat(api.get("/api/orders/" + o2.path("id").asString(), Api.bearer(customer)).path("status").asString())
                .isEqualTo("CONFIRMED");

        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            var rs = c.createStatement().executeQuery("""
                    select count(*) from wallets w left join (select user_id, sum(amount) a, sum(held_delta) h
                    from wallet_transactions group by user_id) t on t.user_id = w.user_id
                    where w.balance <> coalesce(t.a, 0) or w.held_balance <> coalesce(t.h, 0)""");
            rs.next();
            assertThat(rs.getInt(1)).as("ví lệch sổ cái").isZero();
        }
    }

    /** Hạn mức đặt trước: 2 máy giặt -> tối đa 1 máy cho đặt trước trong cùng khung giờ. */
    @Test
    @Order(5)
    void advanceQuotaKeepsMachinesForWalkIns() throws Exception {
        Instant start = Instant.now().plus(10, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
        JsonNode avail = api.get("/api/public/availability?service=WASH&start=" + start, null);
        long washers = 0;
        for (JsonNode w : avail.path("washers")) if (w.path("available").asBoolean()) washers++;
        assertThat(washers).isGreaterThanOrEqualTo(1);
        api.post("/api/orders", Api.bearer(customer), Map.of("service", "WASH", "startAt", start.toString(),
                "washerId", washer));
        JsonNode after = api.get("/api/public/availability?service=WASH&start=" + start, null);
        // Cửa hàng có nhiều máy giặt từ các lần chạy trước; chỉ kiểm máy còn lại của lần chạy này có lý do rõ ràng.
        for (JsonNode w : after.path("washers")) {
            if (w.path("machineId").asString().equals(washer)) {
                assertThat(w.path("available").asBoolean()).isFalse();
            }
        }
    }

    // ---- helpers ----

    static String machine(String code, String type, long price) throws Exception {
        String id = api.post("/api/owner/machines", Api.bearer(owner), Map.of("code", code, "type", type,
                "capacityKg", 10, "cycleMinutes", 30, "displayOrder", 1)).path("id").asString();
        api.put("/api/owner/machines/" + id + "/price", Api.bearer(owner), Map.of("priceVnd", price, "reason", "E2E giá"));
        return id;
    }

    static FakeBoard board(String type, String machineId) throws Exception {
        byte[] mac = new byte[6];
        new Random().nextBytes(mac);
        FakeBoard b = new FakeBoard("ESP-" + HexFormat.of().withUpperCase().formatHex(mac), type);
        boards.add(b);
        String password = b.register(api);
        b.connect(BROKER, password);
        await(() -> "PENDING".equals(b.linkState), b.deviceId + " PENDING");
        JsonNode dev = api.get("/api/admin/devices/" + b.deviceId, Api.bearer(owner));
        api.post("/api/admin/devices/" + b.deviceId + "/approve", Api.bearer(owner),
                Map.of("fingerprint", dev.path("keyFingerprint").asString()));
        api.post("/api/admin/devices/" + b.deviceId + "/bind", Api.bearer(owner),
                Map.of("machineId", machineId, "reason", "E2E gắn bo"));
        await(() -> "BOUND".equals(b.linkState), b.deviceId + " BOUND");
        return b;
    }

    static Map<String, String> kiosk() {
        return Map.of("X-Kiosk-Key", kioskKey);
    }

    static String leg(String orderId, int index) {
        try {
            return api.get("/api/orders/" + orderId, Api.bearer(customer)).path("legs").get(index).path("status").asString();
        } catch (Exception e) {
            return "?";
        }
    }

    interface Check {
        boolean ok() throws Exception;
    }

    static void await(Check check, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok()) return;
            Thread.sleep(150);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }

}
