package com.laundry.devicelink.mqtt;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;

/**
 * The last messages between the backend and the boards on the broker, for the "MQTT traffic" panel of the
 * IoT devices page. In memory only: nothing is persisted, and the oldest entries are dropped once
 * {@link #CAPACITY} is reached.
 */
@Component
public class DeviceTrafficLog {

    public static final int CAPACITY = 1000;
    /** Longer payloads are cut: a board that spams garbage must not fill the memory. */
    static final int MAX_TEXT = 500;

    public enum Kind {
        /** A message the board published ({@code up/...}). */
        UP,
        /** A message the backend published to the board ({@code down/...}). */
        DOWN,
        /** Presence: the board came online or left the broker. */
        INFO
    }

    public record Entry(long seq, Instant time, String deviceId, Kind kind, String topic, String payload) {
    }

    /** {@code reset} is true when the caller's cursor is ahead of this server (it restarted): start over. */
    public record Snapshot(long lastSeq, boolean reset, List<Entry> entries) {
    }

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private long nextSeq = 1;

    public synchronized void record(String deviceId, Kind kind, String topic, String payload) {
        String clipped = payload.length() > MAX_TEXT ? payload.substring(0, MAX_TEXT) + "…" : payload;
        if (entries.size() >= CAPACITY) entries.removeFirst();
        entries.addLast(new Entry(nextSeq++, Instant.now(), deviceId, kind, topic, clipped));
    }

    /** Entries newer than {@code after} (0 = everything kept). */
    public synchronized Snapshot read(long after) {
        long lastSeq = nextSeq - 1;
        boolean reset = after > lastSeq;
        long from = reset ? 0 : after;
        List<Entry> fresh = entries.stream().filter(e -> e.seq() > from).toList();
        return new Snapshot(lastSeq, reset, fresh);
    }
}
