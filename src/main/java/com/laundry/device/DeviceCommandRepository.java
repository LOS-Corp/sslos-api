package com.laundry.device;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.UUID;

public interface DeviceCommandRepository extends JpaRepository<DeviceCommand, UUID> {

    Page<DeviceCommand> findByDeviceIdOrderByCreatedAtDesc(String deviceId, Pageable pageable);

    @Modifying
    @Query("delete from DeviceCommand c where c.deviceId = :deviceId")
    int deleteByDevice(String deviceId);

    @Modifying
    @Query("""
            update DeviceCommand c set c.status = com.laundry.device.DeviceCommand.Status.TIMEOUT
            where c.status = com.laundry.device.DeviceCommand.Status.SENT and c.sentAt < :before
            """)
    int timeoutOlderThan(Instant before);
}
