package com.laundry.user.repository;

import com.laundry.user.entity.BranchAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface BranchAssignmentRepository extends JpaRepository<BranchAssignment, BranchAssignment.Key> {

    boolean existsByIdUserIdAndIdBranchId(UUID userId, UUID branchId);

    @Query("select a.id.branchId from BranchAssignment a where a.id.userId = :userId")
    Set<UUID> findBranchIdsByUserId(UUID userId);

    List<BranchAssignment> findByIdUserId(UUID userId);

    @Modifying
    @Query("delete from BranchAssignment a where a.id.userId = :userId")
    void deleteByUserId(UUID userId);
}
