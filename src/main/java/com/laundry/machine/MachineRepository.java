package com.laundry.machine;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MachineRepository extends JpaRepository<Machine, UUID> {

    Optional<Machine> findByCode(String code);

    boolean existsByCode(String code);

    @Query("select m from Machine m where m.adminStatus <> com.laundry.machine.Machine.AdminStatus.RETIRED order by m.machineType, m.displayOrder, m.code")
    List<Machine> listInService();

    @Query("select m from Machine m order by m.machineType, m.displayOrder, m.code")
    List<Machine> listAll();

    List<Machine> findByMachineTypeOrderByDisplayOrderAscCodeAsc(Machine.Type type);

    List<Machine> findByPriceProfileIdOrderByCode(UUID priceProfileId);

    @Query("select m.priceProfileId, count(m) from Machine m where m.priceProfileId is not null group by m.priceProfileId")
    List<Object[]> countByProfile();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Machine m where m.id = :id")
    Optional<Machine> lock(UUID id);
}
