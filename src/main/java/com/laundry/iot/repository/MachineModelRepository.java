package com.laundry.iot.repository;

import com.laundry.iot.entity.MachineModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MachineModelRepository extends JpaRepository<MachineModel, UUID> {

    List<MachineModel> findAllByOrderByNameAsc();

    boolean existsByBrandId(UUID brandId);

    boolean existsByBrandIdAndNameIgnoreCase(UUID brandId, String name);

    boolean existsByBrandIdAndNameIgnoreCaseAndIdNot(UUID brandId, String name, UUID id);
}
