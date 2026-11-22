package com.laundry.iot.repository;

import com.laundry.iot.entity.MachineBrand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MachineBrandRepository extends JpaRepository<MachineBrand, UUID> {

    List<MachineBrand> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);
}
