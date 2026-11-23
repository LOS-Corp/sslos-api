package com.laundry.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Query("""
            select n from Notification n where n.userId = :userId and n.channel = com.laundry.notification.Notification.Channel.IN_APP
              and n.status = com.laundry.notification.Notification.Status.SENT order by n.scheduledAt desc
            """)
    Page<Notification> inbox(UUID userId, Pageable pageable);

    @Query("""
            select n from Notification n where n.status = com.laundry.notification.Notification.Status.PENDING
              and n.scheduledAt <= :now order by n.scheduledAt limit 200
            """)
    List<Notification> due(Instant now);

    @Modifying
    @Query("""
            update Notification n set n.status = com.laundry.notification.Notification.Status.CANCELLED
            where n.orderId = :orderId and n.status = com.laundry.notification.Notification.Status.PENDING
              and n.type in :types
            """)
    int cancelPending(UUID orderId, List<Notification.Type> types);

    @Modifying
    @Query("update Notification n set n.readAt = :at where n.id = :id and n.userId = :userId and n.readAt is null")
    int markRead(UUID id, UUID userId, Instant at);
}
