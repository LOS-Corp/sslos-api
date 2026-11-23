package com.laundry.device;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MqttAccountRepository extends JpaRepository<MqttAccount, String> {
}
