package com.laundry.machine;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PriceProfileRepository extends JpaRepository<PriceProfile, UUID> {

    @Query("select p from PriceProfile p order by p.machineType, p.priceVnd, p.name")
    List<PriceProfile> listAll();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PriceProfile p where p.id = :id")
    Optional<PriceProfile> lock(UUID id);
}
