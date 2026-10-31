package com.laundry.wallet.repository;

import com.laundry.wallet.entity.WalletEntry;
import com.laundry.wallet.entity.WalletEntryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WalletEntryRepository extends JpaRepository<WalletEntry, Long> {

    boolean existsByWalletIdAndTypeAndReference(UUID walletId, WalletEntryType type, String reference);

    Page<WalletEntry> findByWalletIdOrderByCreatedAtDescIdDesc(UUID walletId, Pageable pageable);
}
