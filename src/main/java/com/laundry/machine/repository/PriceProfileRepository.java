package com.laundry.machine.repository;

import com.laundry.machine.entity.PriceProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PriceProfileRepository extends JpaRepository<PriceProfile, UUID> {

    List<PriceProfile> findAllByOrderByMachineTypeAscNameAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);
}
