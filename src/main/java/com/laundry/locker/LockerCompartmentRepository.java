package com.laundry.locker;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LockerCompartmentRepository extends JpaRepository<LockerCompartment, UUID> {

    List<LockerCompartment> findByLockerIdOrderByCompartmentNo(UUID lockerId);

    Optional<LockerCompartment> findByLockerIdAndCompartmentNo(UUID lockerId, int compartmentNo);
}
