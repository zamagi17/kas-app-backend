package com.zamagi.kas.service;

import com.zamagi.kas.model.Budget;
import com.zamagi.kas.model.Notification;
import com.zamagi.kas.model.ScheduledTransaction;
import com.zamagi.kas.model.Transaksi;
import com.zamagi.kas.model.User;
import com.zamagi.kas.model.UtangPiutang;
import com.zamagi.kas.repository.BudgetRepository;
import com.zamagi.kas.repository.NotificationRepository;
import com.zamagi.kas.repository.ScheduledTransactionRepository;
import com.zamagi.kas.repository.TransaksiRepository;
import com.zamagi.kas.repository.UserRepository;
import com.zamagi.kas.repository.UtangPiutangRepository;
import jakarta.transaction.Transactional;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Jakarta");
    private static final Set<String> AUTO_TYPES = Set.of("DEBT_DUE", "BUDGET_WARNING", "SCHEDULE_DUE");

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final UtangPiutangRepository utangPiutangRepository;
    private final BudgetRepository budgetRepository;
    private final TransaksiRepository transaksiRepository;
    private final ScheduledTransactionRepository scheduleRepository;

    public NotificationService(
            NotificationRepository notificationRepository,
            UserRepository userRepository,
            UtangPiutangRepository utangPiutangRepository,
            BudgetRepository budgetRepository,
            TransaksiRepository transaksiRepository,
            ScheduledTransactionRepository scheduleRepository) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.utangPiutangRepository = utangPiutangRepository;
        this.budgetRepository = budgetRepository;
        this.transaksiRepository = transaksiRepository;
        this.scheduleRepository = scheduleRepository;
    }

    @Scheduled(fixedDelay = 15 * 60 * 1000, initialDelay = 30 * 1000)
    @Transactional
    public void refreshAllUsers() {
        userRepository.findAll().forEach(this::refreshForUser);
    }

    @Transactional
    public List<Notification> getUnreadForUser(User user) {
        refreshForUser(user);
        return notificationRepository.findByUserUsernameAndReadAtIsNullOrderByCreatedAtDesc(user.getUsername());
    }

    @Transactional
    public long countUnreadForUser(User user) {
        refreshForUser(user);
        return notificationRepository.countByUserUsernameAndReadAtIsNull(user.getUsername());
    }

    @Transactional
    public void refreshForUser(User user) {
        Set<String> activeKeys = new HashSet<>();
        generateDebtNotifications(user, activeKeys);
        generateBudgetNotifications(user, activeKeys);
        generateScheduleNotifications(user, activeKeys);
        resolveStaleAutoNotifications(user, activeKeys);
    }

    @Transactional
    public boolean markAsRead(Long id, User user) {
        return notificationRepository.findById(id)
                .filter(notification -> notification.getUser() != null
                        && notification.getUser().getUsername().equals(user.getUsername()))
                .map(notification -> {
                    notification.setReadAt(LocalDateTime.now());
                    notificationRepository.save(notification);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public void markAllAsRead(User user) {
        notificationRepository.findByUserUsernameAndReadAtIsNull(user.getUsername()).forEach(notification -> {
            notification.setReadAt(LocalDateTime.now());
            notificationRepository.save(notification);
        });
    }

    private void generateDebtNotifications(User user, Set<String> activeKeys) {
        LocalDate today = LocalDate.now(DEFAULT_ZONE);
        List<UtangPiutang> items = utangPiutangRepository.findByUserUsernameOrderByStatusAscJatuhTempoAsc(user.getUsername());

        for (UtangPiutang item : items) {
            if (!"Belum Lunas".equals(item.getStatus()) || item.getJatuhTempo() == null) {
                continue;
            }

            long days = ChronoUnit.DAYS.between(today, item.getJatuhTempo());
            if (days > 7) {
                continue;
            }

            String key = "debt:" + item.getId() + ":" + item.getJatuhTempo();
            activeKeys.add(key);
            String severity = days < 0 ? "danger" : "warning";
            String title = item.getJenis() + (days < 0 ? " lewat jatuh tempo" : " segera jatuh tempo");
            String timeInfo = days < 0 ? "lewat " + Math.abs(days) + " hari" : days + " hari lagi";
            String message = item.getNamaPihak() + " - sisa " + formatRp(item.getSisaTagihan()) + ", " + timeInfo;
            createOrUpdate(user, key, "DEBT_DUE", severity, title, message, "/utang-piutang");
        }
    }

    private void generateBudgetNotifications(User user, Set<String> activeKeys) {
        YearMonth month = YearMonth.now(DEFAULT_ZONE);
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        String monthKey = month.toString();

        List<Budget> budgets = budgetRepository.findByUserUsernameAndBulan(user.getUsername(), monthKey);
        List<Transaksi> transactions = transaksiRepository
                .findByUserUsernameAndTanggalBetweenOrderByTanggalDescIdDesc(user.getUsername(), start, end);

        Map<String, Long> spendingByCategory = new HashMap<>();
        for (Transaksi transaction : transactions) {
            String kategori = transaction.getKategori() == null ? "" : transaction.getKategori();
            String keterangan = transaction.getKeterangan() == null ? "" : transaction.getKeterangan();
            boolean isTransferAuto = "Transfer Aset (Auto)".equals(kategori) && keterangan.contains("Mutasi");
            if ("Pengeluaran".equals(transaction.getJenis()) && !isTransferAuto) {
                spendingByCategory.put(kategori,
                        spendingByCategory.getOrDefault(kategori, 0L) + safeNominal(transaction.getNominal()));
            }
        }

        for (Budget budget : budgets) {
            long limit = safeNominal(budget.getLimitBulan());
            if (limit <= 0) {
                continue;
            }

            long used = spendingByCategory.getOrDefault(budget.getKategori(), 0L);
            double percent = (used * 100.0) / limit;
            if (percent < 80) {
                continue;
            }

            String severity = percent > 100 ? "danger" : "warning";
            String key = "budget:" + budget.getId() + ":" + monthKey + ":" + severity;
            activeKeys.add(key);
            String title = percent > 100 ? "Budget terlampaui" : "Budget hampir habis";
            String message = budget.getKategori() + " sudah " + Math.round(percent) + "% ("
                    + formatRp(used) + " dari " + formatRp(limit) + ")";
            createOrUpdate(user, key, "BUDGET_WARNING", severity, title, message, "/budget");
        }
    }

    private void generateScheduleNotifications(User user, Set<String> activeKeys) {
        OffsetDateTime now = OffsetDateTime.now(DEFAULT_ZONE);
        OffsetDateTime tomorrow = now.plusDays(1);
        List<ScheduledTransaction> schedules = scheduleRepository
                .findByUserUsernameOrderByNextRunAtAscIdDesc(user.getUsername());

        for (ScheduledTransaction schedule : schedules) {
            if (!Boolean.TRUE.equals(schedule.getActive()) || schedule.getNextRunAt() == null) {
                continue;
            }
            if (schedule.getNextRunAt().isBefore(now) || schedule.getNextRunAt().isAfter(tomorrow)) {
                continue;
            }

            String key = "schedule:" + schedule.getId() + ":" + schedule.getNextRunAt();
            activeKeys.add(key);
            String message = schedule.getTitle() + " - "
                    + schedule.getNextRunAt().atZoneSameInstant(DEFAULT_ZONE).toLocalDateTime();
            createOrUpdate(user, key, "SCHEDULE_DUE", "info",
                    "Schedule segera berjalan", message, "/schedule");
        }
    }

    private void createOrUpdate(
            User user,
            String dedupeKey,
            String type,
            String severity,
            String title,
            String message,
            String targetUrl) {
        Notification notification = notificationRepository
                .findByUserUsernameAndDedupeKey(user.getUsername(), dedupeKey)
                .orElseGet(Notification::new);

        if (notification.getReadAt() != null) {
            return;
        }

        notification.setUser(user);
        notification.setDedupeKey(dedupeKey);
        notification.setType(type);
        notification.setSeverity(severity);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setTargetUrl(targetUrl);
        notificationRepository.save(notification);
    }

    private void resolveStaleAutoNotifications(User user, Set<String> activeKeys) {
        notificationRepository.findByUserUsernameAndReadAtIsNull(user.getUsername()).forEach(notification -> {
            if (AUTO_TYPES.contains(notification.getType()) && !activeKeys.contains(notification.getDedupeKey())) {
                notification.setReadAt(LocalDateTime.now());
                notificationRepository.save(notification);
            }
        });
    }

    private long safeNominal(Long value) {
        return value == null ? 0L : value;
    }

    private String formatRp(Long value) {
        NumberFormat formatter = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("id-ID"));
        formatter.setMaximumFractionDigits(0);
        return formatter.format(safeNominal(value));
    }
}
