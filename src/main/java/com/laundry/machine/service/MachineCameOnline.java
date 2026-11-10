package com.laundry.machine.service;

import java.util.UUID;

/**
 * A machine sent a heartbeat after being offline. Published inside the
 * heartbeat transaction; listeners act after commit (e.g. the IoT module
 * starts the commissioning self-test of a freshly provisioned board).
 */
public record MachineCameOnline(UUID machineId, String machineCode) {
}
