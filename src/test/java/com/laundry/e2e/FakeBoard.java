package com.laundry.e2e;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bo mạch giả nói đúng giao thức firmware (sslos-iot/common/NetLink.h, BackendLink.h): đăng ký HTTP bằng chữ ký
 * ECDSA P-256, mở mật khẩu MQTT được niêm phong, bắt tay HELLO/challenge/proof, trả lời lệnh như máy thật.
 */
public final class FakeBoard implements AutoCloseable {

    static final String ROOT = "laundryos/v1/devices/";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    final String deviceId;
    final String deviceType;
    private final KeyPair keys;
    final String publicKeyB64;
    private final String bootId = "boot-" + System.nanoTime();
    private MqttClient mqtt;
    volatile String linkState = "NONE";
    volatile String machineCode = "";
    volatile String authorizationId;
    volatile String runId;
    final List<JsonNode> commands = new CopyOnWriteArrayList<>();
    final List<String> approvals = new CopyOnWriteArrayList<>();

    FakeBoard(String deviceId, String deviceType) throws Exception {
        this.deviceId = deviceId;
        this.deviceType = deviceType;
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        this.keys = g.generateKeyPair();
        this.publicKeyB64 = Base64.getEncoder().encodeToString(rawPoint((ECPublicKey) keys.getPublic()));
    }

    /** POST /api/devices/register/challenge + register; trả mật khẩu MQTT đã giải niêm phong. */
    String register(Api api) throws Exception {
        JsonNode ch = api.post("/api/devices/register/challenge", null, JSON.createObjectNode().put("deviceId", deviceId));
        String nonce = ch.path("nonce").asString();
        ObjectNode body = JSON.createObjectNode().put("deviceId", deviceId).put("firmwareVersion", "fake-1.0")
                .put("deviceType", deviceType).put("publicKey", publicKeyB64).put("nonce", nonce)
                .put("signature", sign("LAUNDRYOS-REGISTER|" + deviceId + "|" + nonce)).put("rssi", -50);
        JsonNode acc = api.post("/api/devices/register", null, body);
        return open(acc.path("epk").asString(), acc.path("iv").asString(), acc.path("ct").asString());
    }

    void connect(String broker, String password) throws Exception {
        mqtt = new MqttClient(broker, deviceId, new MemoryPersistence());
        MqttConnectOptions o = new MqttConnectOptions();
        o.setUserName(deviceId);
        o.setPassword(password.toCharArray());
        o.setCleanSession(true);
        o.setWill(ROOT + deviceId + "/presence", "offline".getBytes(), 1, true);
        mqtt.setCallback(new org.eclipse.paho.client.mqttv3.MqttCallback() {
            public void connectionLost(Throwable cause) {
            }

            public void messageArrived(String topic, MqttMessage message) {
                try {
                    onMessage(topic.substring((ROOT + deviceId + "/down/").length()), JSON.readTree(message.getPayload()));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
            }
        });
        mqtt.connect(o);
        mqtt.subscribe(ROOT + deviceId + "/down/#", 1);
        MqttMessage online = new MqttMessage("online".getBytes());
        online.setQos(1);
        online.setRetained(true);
        mqtt.publish(ROOT + deviceId + "/presence", online);
    }

    private void onMessage(String channel, JsonNode body) throws Exception {
        switch (channel) {
            case "link" -> {
                linkState = body.path("state").asString();
                machineCode = body.path("machineCode").asString("");
                // Như firmware: đã gắn máy thì báo trạng thái (backend tự gửi self-test lắp đặt khi nhận heartbeat đầu).
                if ("BOUND".equals(linkState)) up("heartbeat", identity().put("status", "AVAILABLE").put("running", false));
                if ("HELLO_REQUIRED".equals(linkState)) {
                    up("hello", JSON.createObjectNode().put("deviceId", deviceId).put("firmwareVersion", "fake-1.0")
                            .put("deviceType", deviceType).put("publicKey", publicKeyB64).put("ip", "127.0.0.1")
                            .put("rssi", -50));
                }
            }
            case "challenge" -> {
                String nonce = body.path("nonce").asString();
                up("proof", JSON.createObjectNode().put("nonce", nonce)
                        .put("signature", sign("LAUNDRYOS-AUTH|" + deviceId + "|" + nonce)));
            }
            case "command" -> {
                commands.add(body);
                String type = body.path("type").asString();
                String id = body.path("authorizationId").asString("");
                switch (type) {
                    case "AUTHORIZE_START" -> {
                        authorizationId = id;
                        auth("AUTH_ACK", id, true, "ACCEPTED");
                    }
                    case "START_APPROVAL" -> {
                        approvals.add(body.path("result").asString());
                        if ("APPROVED".equals(body.path("result").asString())) {
                            runId = body.path("runId").asString();
                            auth("STARTED", id, true, "STARTED");
                        } else {
                            auth("START_REJECTED", id, false, "REJECTED");
                        }
                    }
                    case "REVOKE_START" -> authorizationId = null;
                    case "LOAD_VERDICT" -> {
                    }
                    case "SELF_TEST" -> {
                        String commandId = body.path("commandId").asString();
                        up("command/ack", JSON.createObjectNode().put("commandId", commandId).put("command", type)
                                .put("success", true).put("reason", ""));
                        ObjectNode result = identity().put("event", "SELF_TEST_RESULT").put("commandId", commandId)
                                .put("passed", true);
                        result.putObject("checks").put("doorClosed", true).put("motorDriven", true)
                                .put("startButton", true).put("loadCell", "DISABLED").put("oled", true);
                        up("event", result);
                    }
                    default -> up("command/ack", JSON.createObjectNode().put("commandId", body.path("commandId").asString())
                            .put("command", type).put("success", true).put("reason", ""));
                }
            }
            default -> {
            }
        }
    }

    /** Khách bấm START trên máy. */
    void pressStart() throws Exception {
        ObjectNode b = identity().put("type", "START_REQUEST").put("authorizationId", authorizationId)
                .put("startRequestId", bootId + "-1");
        up("auth", b);
    }

    /** Máy chạy xong chu kỳ. */
    void complete() throws Exception {
        up("status", identity().put("status", "AVAILABLE").put("phase", "COMPLETED").put("running", false)
                .put("runId", runId).put("remainingSeconds", 0));
    }

    private void auth(String type, String authorizationId, boolean ok, String result) throws Exception {
        up("auth", identity().put("type", type).put("authorizationId", authorizationId).put("commandId", "")
                .put("startRequestId", bootId + "-1").put("runId", runId == null ? "" : runId).put("success", ok)
                .put("result", result).put("reason", ""));
    }

    private ObjectNode identity() {
        return JSON.createObjectNode().put("machineId", machineCode).put("deviceId", deviceId)
                .put("firmwareVersion", "fake-1.0").put("machineType", deviceType).put("bootId", bootId);
    }

    private void up(String channel, JsonNode body) throws Exception {
        mqtt.publish(ROOT + deviceId + "/up/" + channel, new MqttMessage(JSON.writeValueAsBytes(body)));
    }

    private String sign(String message) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(keys.getPrivate());
        s.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(s.sign());
    }

    /** Ngược của DeviceKeys.seal. */
    private String open(String epkB64, String ivB64, String ctB64) throws Exception {
        byte[] epk = Base64.getDecoder().decode(epkB64);
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(keys.getPrivate());
        ka.doPhase(toPublicKey(epk), true);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(ka.generateSecret());
        sha.update(("LAUNDRYOS-CRED|" + deviceId).getBytes(StandardCharsets.UTF_8));
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sha.digest(), "AES"),
                new GCMParameterSpec(128, Base64.getDecoder().decode(ivB64)));
        c.updateAAD(deviceId.getBytes(StandardCharsets.UTF_8));
        return new String(c.doFinal(Base64.getDecoder().decode(ctB64)), StandardCharsets.UTF_8);
    }

    private static byte[] rawPoint(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 4;
        put(key.getW().getAffineX(), out, 1);
        put(key.getW().getAffineY(), out, 33);
        return out;
    }

    private static void put(BigInteger v, byte[] out, int offset) {
        byte[] b = v.toByteArray();
        int start = Math.max(0, b.length - 32);
        int len = b.length - start;
        System.arraycopy(b, start, out, offset + 32 - len, len);
    }

    private static PublicKey toPublicKey(byte[] raw) throws Exception {
        AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
        p.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec spec = p.getParameterSpec(ECParameterSpec.class);
        ECPoint point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(raw, 1, 33)),
                new BigInteger(1, Arrays.copyOfRange(raw, 33, 65)));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, spec));
    }

    @Override
    public void close() throws Exception {
        if (mqtt != null && mqtt.isConnected()) {
            MqttMessage off = new MqttMessage("offline".getBytes());
            off.setQos(1);
            off.setRetained(true);
            mqtt.publish(ROOT + deviceId + "/presence", off);
            mqtt.disconnect();
        }
    }
}
