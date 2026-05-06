package com.zamagi.kas.controller;

import com.zamagi.kas.model.Notification;
import com.zamagi.kas.model.User;
import com.zamagi.kas.repository.UserRepository;
import com.zamagi.kas.service.NotificationService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
@CrossOrigin(origins = "*")
public class NotificationController {

    private final NotificationService notificationService;
    private final UserRepository userRepository;

    public NotificationController(NotificationService notificationService, UserRepository userRepository) {
        this.notificationService = notificationService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<List<Notification>> getUnread(Authentication auth) {
        User user = getCurrentUser(auth);
        return ResponseEntity.ok(notificationService.getUnreadForUser(user));
    }

    @GetMapping("/count")
    public ResponseEntity<Map<String, Long>> countUnread(Authentication auth) {
        User user = getCurrentUser(auth);
        return ResponseEntity.ok(Map.of("unread", notificationService.countUnreadForUser(user)));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<?> markAsRead(@PathVariable Long id, Authentication auth) {
        User user = getCurrentUser(auth);
        if (!notificationService.markAsRead(id, user)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("message", "Notifikasi ditandai dibaca"));
    }

    @PatchMapping("/read-all")
    public ResponseEntity<?> markAllAsRead(Authentication auth) {
        User user = getCurrentUser(auth);
        notificationService.markAllAsRead(user);
        return ResponseEntity.ok(Map.of("message", "Semua notifikasi ditandai dibaca"));
    }

    private User getCurrentUser(Authentication auth) {
        return userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new RuntimeException("User tidak ditemukan"));
    }
}
