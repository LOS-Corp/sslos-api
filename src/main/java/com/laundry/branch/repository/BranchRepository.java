package com.laundry.branch.repository;

import com.laundry.branch.entity.Branch;
import com.laundry.branch.entity.BranchStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BranchRepository extends JpaRepository<Branch, UUID> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    Optional<Branch> findByNameIgnoreCase(String name);

    List<Branch> findByStatusOrderByNameAsc(BranchStatus status);

    @Query("SELECT b FROM Branch b WHERE " +
           "(:status IS NULL OR b.status = :status) AND " +
           "(:q IS NULL OR :q = '' OR " +
           " LOWER(b.name) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           " LOWER(b.address) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           " LOWER(b.phone) LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<Branch> searchBranches(@Param("q") String q,
                                @Param("status") BranchStatus status,
                                Pageable pageable);
}
