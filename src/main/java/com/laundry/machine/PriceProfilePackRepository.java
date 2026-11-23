package com.laundry.machine;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface PriceProfilePackRepository extends JpaRepository<PriceProfilePack, UUID> {

    List<PriceProfilePack> findByProfileIdOrderByDisplayOrderAscMinutesAsc(UUID profileId);

    @Query("select p from PriceProfilePack p order by p.profileId, p.displayOrder, p.minutes")
    List<PriceProfilePack> listAll();
}
