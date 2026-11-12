package com.laundry.devicelink;

/**
 * Lets another component borrow a board's serial port, e.g. to flash new firmware
 * with esptool. Only one program can hold a COM port, so the link closes it and
 * stops reopening it until {@link #reclaim} is called.
 */
public interface SerialPortControl {

    /** Closes {@code port} (the board goes offline) and keeps it closed. */
    void release(String port);

    /** Lets the scanner open {@code port} again; it does so right away. */
    void reclaim(String port);
}
