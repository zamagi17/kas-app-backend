package com.zamagi.kas.controller;

import com.zamagi.kas.model.ScheduledTransaction;
import com.zamagi.kas.model.User;
import com.zamagi.kas.repository.ScheduledTransactionRepository;
import com.zamagi.kas.repository.UserRepository;
import com.zamagi.kas.service.ScheduledTransactionService;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/schedules")
@CrossOrigin(origins = "*")
public class ScheduledTransactionController {

    private final ScheduledTransactionRepository scheduleRepository;
    private final UserRepository userRepository;
    private final ScheduledTransactionService scheduleService;

    public ScheduledTransactionController(
            ScheduledTransactionRepository scheduleRepository,
            UserRepository userRepository,
            ScheduledTransactionService scheduleService) {
        this.scheduleRepository = scheduleRepository;
        this.userRepository = userRepository;
        this.scheduleService = scheduleService;
    }

    @GetMapping
    public ResponseEntity<List<ScheduledTransaction>> getAll(Authentication auth) {
        return ResponseEntity.ok(scheduleRepository.findByUserUsernameOrderByNextRunAtAscIdDesc(auth.getName()));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody ScheduledTransaction schedule, Authentication auth) {
        String error = scheduleService.validate(schedule);
        if (error != null) {
            return ResponseEntity.badRequest().body(error);
        }

        User user = userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new RuntimeException("User tidak ditemukan"));
        schedule.setUser(user);
        scheduleService.normalizeBeforeSave(schedule);

        return ResponseEntity.ok(scheduleRepository.save(schedule));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @RequestBody ScheduledTransaction updated,
            Authentication auth) {
        String error = scheduleService.validate(updated);
        if (error != null) {
            return ResponseEntity.badRequest().body(error);
        }

        return scheduleRepository.findById(id)
                .filter(schedule -> schedule.getUser() != null && schedule.getUser().getUsername().equals(auth.getName()))
                .map(schedule -> {
                    schedule.setTitle(updated.getTitle());
                    schedule.setFrequency(updated.getFrequency());
                    schedule.setInterval(updated.getInterval());
                    schedule.setDayOfWeek(updated.getDayOfWeek());
                    schedule.setDayOfMonth(updated.getDayOfMonth());
                    schedule.setStartDate(updated.getStartDate());
                    schedule.setTimeOfDay(updated.getTimeOfDay());
                    schedule.setTimezone(updated.getTimezone());
                    schedule.setActive(updated.getActive());
                    schedule.setJenis(updated.getJenis());
                    schedule.setKategori(updated.getKategori());
                    schedule.setSumberDana(updated.getSumberDana());
                    schedule.setNominal(updated.getNominal());
                    schedule.setKeterangan(updated.getKeterangan());
                    scheduleService.normalizeBeforeSave(schedule);
                    return ResponseEntity.ok(scheduleRepository.save(schedule));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/active")
    @Transactional
    public ResponseEntity<?> setActive(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> payload,
            Authentication auth) {
        Boolean active = payload.get("active");
        if (active == null) {
            return ResponseEntity.badRequest().body("Status active wajib dikirim");
        }

        return scheduleRepository.findById(id)
                .filter(schedule -> schedule.getUser() != null && schedule.getUser().getUsername().equals(auth.getName()))
                .map(schedule -> {
                    schedule.setActive(active);
                    if (active && schedule.getNextRunAt() != null && !schedule.getNextRunAt().isAfter(OffsetDateTime.now())) {
                        schedule.setNextRunAt(scheduleService.calculateFirstRun(schedule));
                    }
                    return ResponseEntity.ok(scheduleRepository.save(schedule));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id, Authentication auth) {
        return scheduleRepository.findById(id)
                .filter(schedule -> schedule.getUser() != null && schedule.getUser().getUsername().equals(auth.getName()))
                .map(schedule -> {
                    scheduleRepository.delete(schedule);
                    return ResponseEntity.ok(Map.of("message", "Schedule dihapus"));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
