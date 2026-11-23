package com.laundry.device;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<Device, String> {

    Optional<Device> findByMachineId(UUID machineId);

    Optional<Device> findByLockerId(UUID lockerId);

    long countByLinkState(Device.LinkState state);

    @Query("select d from Device d order by d.linkState, d.id")
    List<Device> listAll();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Device d where d.id = :id")
    Optional<Device> lock(String id);

    @Modifying
    @Query("update Device d set d.online = :online, d.lastSeenAt = :at where d.id = :id")
    int setOnline(String id, boolean online, Instant at);

    @Modifying
    @Query("update Device d set d.online = false where d.online = true")
    int markAllOffline();

    @Modifying
    @Query(value = "update devices set last_status = cast(:status as jsonb), last_seen_at = :at, online = true where id = :id",
            nativeQuery = true)
    int saveStatus(String id, String status, Instant at);
}
