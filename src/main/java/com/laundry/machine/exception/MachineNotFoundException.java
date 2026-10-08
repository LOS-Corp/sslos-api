package com.laundry.machine.exception;

/**
 * Thrown when a machine lookup by machineCode or id yields no result.
 * Translated to HTTP 404 by {@code GlobalExceptionHandler}.
 */
public class MachineNotFoundException extends RuntimeException {

    public MachineNotFoundException(String message) {
        super(message);
    }

    public static MachineNotFoundException byCode(String machineCode) {
        return new MachineNotFoundException("Machine not found: " + machineCode);
    }
}
