package com.laundry.machine.repository;

import com.laundry.machine.entity.DeviceEvent;
import com.laundry.machine.entity.DeviceEventType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Repository for DeviceEvent entities.
 */
@Repository
public interface DeviceEventRepository extends JpaRepository<DeviceEvent, UUID> {

    Page<DeviceEvent> findByMachineIdOrderByCreatedAtDesc(UUID machineId, Pageable pageable);

    List<DeviceEvent> findByMachineIdAndCreatedAtAfterOrderByCreatedAtDesc(
            UUID machineId, OffsetDateTime after);

    List<DeviceEvent> findByMachineIdAndEventTypeOrderByCreatedAtDesc(
            UUID machineId, DeviceEventType eventType);

    /** Count events of a specific type in a time window (for analytics). */
    long countByMachineIdAndEventTypeAndCreatedAtBetween(
            UUID machineId, DeviceEventType eventType,
            OffsetDateTime from, OffsetDateTime to);
}
