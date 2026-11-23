package com.laundry.e2e;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** HTTP tối giản cho test đầu-cuối: ném lỗi kèm nội dung khi status không như mong đợi. */
final class Api {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String base;
    int lastStatus;

    Api(String base) {
        this.base = base;
    }

    JsonNode get(String path, Map<String, String> headers) throws Exception {
        return send("GET", path, headers, null, 200);
    }

    JsonNode post(String path, Map<String, String> headers, Object body) throws Exception {
        return send("POST", path, headers, body, -1);
    }

    JsonNode put(String path, Map<String, String> headers, Object body) throws Exception {
        return send("PUT", path, headers, body, -1);
    }

    JsonNode send(String method, String path, Map<String, String> headers, Object body, int expect) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
        if (headers != null) headers.forEach(b::header);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        lastStatus = r.statusCode();
        boolean ok = expect > 0 ? r.statusCode() == expect : r.statusCode() / 100 == 2;
        if (!ok) throw new AssertionError(method + " " + path + " -> " + r.statusCode() + " " + r.body());
        return r.body().isEmpty() ? JSON.createObjectNode() : JSON.readTree(r.body());
    }

    static Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }
}
