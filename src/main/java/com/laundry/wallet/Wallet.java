package com.laundry.wallet;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/** balance = khả dụng; heldBalance = đang giữ cho yêu cầu rút. Không bao giờ âm (CHECK ở DB). */
@Entity
@Table(name = "wallets")
@Getter
@Setter
public class Wallet {
    @Id
    private UUID userId;
    private long balance;
    private long heldBalance;
    @CreationTimestamp
    private Instant createdAt;
    @UpdateTimestamp
    private Instant updatedAt;
    @Version
    private long version;
}
