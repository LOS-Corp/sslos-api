package com.laundry.iot.repository;

import com.laundry.iot.entity.PairingSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PairingSessionRepository extends JpaRepository<PairingSession, UUID> {

    Optional<PairingSession> findFirstByDeviceIdAndStatusOrderByCreatedAtDesc(
            String deviceId, PairingSession.Status status);

    List<PairingSession> findByMachineIdAndStatus(UUID machineId, PairingSession.Status status);

    List<PairingSession> findByDeviceIdAndStatus(String deviceId, PairingSession.Status status);
}
