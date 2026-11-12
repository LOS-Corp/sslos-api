package com.laundry.store.repository;

import com.laundry.store.entity.StoreSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreSettingsRepository extends JpaRepository<StoreSettings, Short> {
}
