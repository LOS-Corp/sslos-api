package com.laundry.wallet.repository;

import com.laundry.wallet.entity.WalletWithdrawal;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletWithdrawalRepository extends JpaRepository<WalletWithdrawal, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WalletWithdrawal w where w.id = :id")
    Optional<WalletWithdrawal> findByIdForUpdate(@Param("id") UUID id);

    List<WalletWithdrawal> findByCustomerIdOrderByRequestedAtDesc(UUID customerId);

    List<WalletWithdrawal> findByStatusOrderByRequestedAtAsc(WalletWithdrawal.Status status);
}
