package com.laundry.machine.exception;

import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;

/**
 * Thrown when a machine lookup by machineCode or id yields no result.
 * Translated to HTTP 404 by {@code GlobalExceptionHandler}.
 */
public class MachineNotFoundException extends BusinessException {

    public MachineNotFoundException(String message) {
        super(ErrorCode.MACHINE_NOT_FOUND, message);
    }

    public static MachineNotFoundException byCode(String machineCode) {
        return new MachineNotFoundException("Machine not found: " + machineCode);
    }
}
