package com.laundry.iot.repository;

import com.laundry.iot.entity.IotDevice;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IotDeviceRepository extends JpaRepository<IotDevice, UUID> {

    Optional<IotDevice> findByDeviceId(String deviceId);

    /** Row lock so concurrent challenge/activate calls for one device serialize. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM IotDevice d WHERE d.deviceId = :deviceId")
    Optional<IotDevice> findByDeviceIdForUpdate(@Param("deviceId") String deviceId);

    List<IotDevice> findAllByOrderByEnrolledAtDesc();
}
