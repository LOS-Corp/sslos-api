package com.laundry.device.link;

import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;

public class DeviceNotConnectedException extends BusinessException {
    public DeviceNotConnectedException(String message) {
        super(ErrorCode.DEVICE_NOT_CONNECTED, message);
    }
}
