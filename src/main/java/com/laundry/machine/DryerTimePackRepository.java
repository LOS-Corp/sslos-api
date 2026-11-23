package com.laundry.machine;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DryerTimePackRepository extends JpaRepository<DryerTimePack, UUID> {

    List<DryerTimePack> findByMachineIdOrderByDisplayOrderAscMinutesAsc(UUID machineId);

    List<DryerTimePack> findByMachineIdAndActiveTrueOrderByMinutesAsc(UUID machineId);
}
