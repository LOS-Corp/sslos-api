package com.laundry.kiosk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface KioskRepository extends JpaRepository<Kiosk, UUID> {

    Optional<Kiosk> findByApiKeyHash(String apiKeyHash);

    boolean existsByCode(String code);

    @Modifying
    @Query("update Kiosk k set k.lastSeenAt = :at where k.id = :id")
    void touch(UUID id, Instant at);
}
