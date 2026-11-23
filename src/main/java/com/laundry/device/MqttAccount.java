package com.laundry.device;

import com.laundry.common.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/** Tài khoản broker; EMQX đọc bảng này (password_hash = hex(SHA-256(salt || password))). */
@Entity
@Table(name = "mqtt_accounts")
@Getter
@Setter
public class MqttAccount extends AssignedIdEntity<String> {
    @Id
    private String username;
    private String passwordHash;
    private String salt;
    private boolean isSuperuser;
    private String deviceId;
    private boolean enabled = true;
    @CreationTimestamp
    private Instant createdAt;
    private Instant rotatedAt;

    @Override
    public String getId() {
        return username;
    }
}
