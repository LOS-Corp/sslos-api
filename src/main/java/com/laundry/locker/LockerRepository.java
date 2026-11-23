package com.laundry.locker;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LockerRepository extends JpaRepository<Locker, UUID> {

    Optional<Locker> findByCode(String code);

    boolean existsByCode(String code);
}
