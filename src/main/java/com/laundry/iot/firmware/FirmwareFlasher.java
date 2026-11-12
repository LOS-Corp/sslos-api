package com.laundry.iot.firmware;

import java.nio.file.Path;
import java.util.function.Consumer;

/** Writes an application image to the board on {@code port}; the port must be free. */
public interface FirmwareFlasher {

    /**
     * Blocks until flashing ends.
     *
     * @param output receives each line the flasher prints (progress, errors)
     * @return the flasher's exit code; 0 = written and verified
     */
    int flash(String port, Path image, Consumer<String> output) throws Exception;
}
