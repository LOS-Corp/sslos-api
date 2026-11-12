package com.laundry.devicelink;

/**
 * One open physical link to a board (a USB serial port). Implementations must
 * make {@link #writeLine} safe to call from several threads.
 */
public interface DeviceConnection {

    /** Stable name of the link, e.g. {@code COM3} or {@code /dev/ttyUSB0}. */
    String name();

    /** Writes one line; the newline is added by the implementation. */
    void writeLine(String line);
}
