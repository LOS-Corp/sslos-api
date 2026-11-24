package com.laundry.machine;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MachineReservationRepository extends JpaRepository<MachineReservation, UUID> {

    /** Các khoảng active của một máy chạm vào [from, to). */
    @Query("""
            select r from MachineReservation r
            where r.machineId = :machineId and r.active and r.startsAt < :to and r.endsAt > :from
            order by r.startsAt
            """)
    List<MachineReservation> overlapping(UUID machineId, Instant from, Instant to);

    @Query("""
            select r from MachineReservation r
            where r.machineId in :machineIds and r.active and r.startsAt < :to and r.endsAt > :from
            order by r.machineId, r.startsAt
            """)
    List<MachineReservation> overlapping(Collection<UUID> machineIds, Instant from, Instant to);

    @Query("""
            select r from MachineReservation r
            where r.machineId = :machineId and r.kind = com.laundry.machine.MachineReservation.Kind.MAINTENANCE
              and r.active and r.endsAt > :from order by r.startsAt
            """)
    List<MachineReservation> upcomingMaintenance(UUID machineId, Instant from);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    /** Máy đang trong một khoảng bảo trì đã lên lịch. */
    @Query("""
            select distinct r.machineId from MachineReservation r
            where r.kind = com.laundry.machine.MachineReservation.Kind.MAINTENANCE and r.active
              and r.startsAt <= :now and r.endsAt > :now
            """)
    List<UUID> inMaintenanceWindow(Instant now);

    @Query("select r from MachineReservation r where r.id = :id")
    Optional<MachineReservation> lock(UUID id);
}
