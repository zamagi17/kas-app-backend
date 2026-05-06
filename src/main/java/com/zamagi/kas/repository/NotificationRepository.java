package com.zamagi.kas.repository;

import com.zamagi.kas.model.Notification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByUserUsernameAndReadAtIsNullOrderByCreatedAtDesc(String username);

    List<Notification> findByUserUsernameAndReadAtIsNull(String username);

    Optional<Notification> findByUserUsernameAndDedupeKey(String username, String dedupeKey);

    long countByUserUsernameAndReadAtIsNull(String username);
}
