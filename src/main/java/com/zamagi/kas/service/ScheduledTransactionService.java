package com.zamagi.kas.service;

import com.zamagi.kas.model.ScheduledTransaction;
import com.zamagi.kas.model.Transaksi;
import com.zamagi.kas.repository.ScheduledTransactionRepository;
import com.zamagi.kas.repository.TransaksiRepository;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ScheduledTransactionService {

    private static final List<String> FREQUENCY_VALID = List.of("daily", "weekly", "monthly");
    private static final List<String> JENIS_VALID = List.of("Pemasukan", "Pengeluaran");

    private final ScheduledTransactionRepository scheduleRepository;
    private final TransaksiRepository transaksiRepository;

    public ScheduledTransactionService(
            ScheduledTransactionRepository scheduleRepository,
            TransaksiRepository transaksiRepository) {
        this.scheduleRepository = scheduleRepository;
        this.transaksiRepository = transaksiRepository;
    }

    public String validate(ScheduledTransaction schedule) {
        if (schedule.getTitle() == null || schedule.getTitle().isBlank()) {
            return "Nama schedule wajib diisi";
        }
        if (schedule.getTitle().length() > 100) {
            return "Nama schedule maksimal 100 karakter";
        }
        if (schedule.getFrequency() == null || !FREQUENCY_VALID.contains(schedule.getFrequency())) {
            return "Frequency harus daily, weekly, atau monthly";
        }
        if (schedule.getInterval() == null || schedule.getInterval() < 1 || schedule.getInterval() > 365) {
            return "Interval harus antara 1 sampai 365";
        }
        if ("weekly".equals(schedule.getFrequency())
                && (schedule.getDayOfWeek() == null || schedule.getDayOfWeek() < 1 || schedule.getDayOfWeek() > 7)) {
            return "Hari mingguan wajib dipilih";
        }
        if ("monthly".equals(schedule.getFrequency())
                && (schedule.getDayOfMonth() == null || schedule.getDayOfMonth() < 1 || schedule.getDayOfMonth() > 31)) {
            return "Tanggal bulanan harus antara 1 sampai 31";
        }
        if (schedule.getStartDate() == null) {
            return "Tanggal mulai wajib diisi";
        }
        if (schedule.getTimeOfDay() == null) {
            schedule.setTimeOfDay(LocalTime.of(8, 0));
        }
        if (schedule.getTimezone() == null || schedule.getTimezone().isBlank()) {
            schedule.setTimezone("Asia/Jakarta");
        }
        try {
            ZoneId.of(schedule.getTimezone());
        } catch (Exception e) {
            return "Timezone tidak valid";
        }
        if (schedule.getNominal() == null || schedule.getNominal() <= 0) {
            return "Nominal harus lebih dari 0";
        }
        if (schedule.getNominal() > 999_999_999_999L) {
            return "Nominal maksimal Rp 999.999.999.999";
        }
        if (schedule.getJenis() == null || !JENIS_VALID.contains(schedule.getJenis())) {
            return "Jenis schedule harus Pemasukan atau Pengeluaran";
        }
        if (schedule.getKategori() == null || schedule.getKategori().isBlank()) {
            return "Kategori wajib diisi";
        }
        if (schedule.getSumberDana() == null || schedule.getSumberDana().isBlank()) {
            return "Sumber dana wajib diisi";
        }
        if (schedule.getKeterangan() != null && schedule.getKeterangan().length() > 500) {
            return "Keterangan maksimal 500 karakter";
        }
        return null;
    }

    public void normalizeBeforeSave(ScheduledTransaction schedule) {
        schedule.setFrequency(schedule.getFrequency().toLowerCase());
        schedule.setInterval(Math.max(schedule.getInterval(), 1));
        if (!"weekly".equals(schedule.getFrequency())) {
            schedule.setDayOfWeek(null);
        }
        if (!"monthly".equals(schedule.getFrequency())) {
            schedule.setDayOfMonth(null);
        }
        if (schedule.getActive() == null) {
            schedule.setActive(true);
        }
        schedule.setNextRunAt(calculateFirstRun(schedule));
    }

    public OffsetDateTime calculateFirstRun(ScheduledTransaction schedule) {
        ZoneId zoneId = ZoneId.of(schedule.getTimezone());
        OffsetDateTime now = OffsetDateTime.now(zoneId);
        OffsetDateTime candidate = atScheduledTime(schedule, schedule.getStartDate(), zoneId);
        if ("weekly".equals(schedule.getFrequency())) {
            candidate = nextWeeklyOnOrAfter(schedule, schedule.getStartDate(), schedule.getDayOfWeek(), zoneId);
        } else if ("monthly".equals(schedule.getFrequency())) {
            candidate = monthlyAt(schedule, schedule.getStartDate().getYear(), schedule.getStartDate().getMonthValue(),
                    schedule.getDayOfMonth(), zoneId);
            if (candidate.toLocalDate().isBefore(schedule.getStartDate())) {
                candidate = addMonths(schedule, candidate);
            }
        }

        while (!candidate.isAfter(now)) {
            candidate = calculateNextRunAfter(schedule, candidate);
        }
        return candidate;
    }

    public OffsetDateTime calculateNextRunAfter(ScheduledTransaction schedule, OffsetDateTime previousRun) {
        if ("daily".equals(schedule.getFrequency())) {
            return previousRun.plusDays(schedule.getInterval());
        }
        if ("weekly".equals(schedule.getFrequency())) {
            return previousRun.plusWeeks(schedule.getInterval());
        }
        return addMonths(schedule, previousRun);
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 15000)
    @Transactional
    public void processDueSchedules() {
        OffsetDateTime now = OffsetDateTime.now();
        List<ScheduledTransaction> dueSchedules = scheduleRepository
                .findTop100ByActiveTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(now);

        for (ScheduledTransaction schedule : dueSchedules) {
            int generated = 0;
            while (schedule.getNextRunAt() != null && !schedule.getNextRunAt().isAfter(now) && generated < 24) {
                createTransaction(schedule, schedule.getNextRunAt());
                schedule.setNextRunAt(calculateNextRunAfter(schedule, schedule.getNextRunAt()));
                generated++;
            }
            scheduleRepository.save(schedule);
        }
    }

    private void createTransaction(ScheduledTransaction schedule, OffsetDateTime runAt) {
        ZoneId zoneId = ZoneId.of(schedule.getTimezone());
        Transaksi transaksi = new Transaksi();
        transaksi.setUser(schedule.getUser());
        transaksi.setTanggal(runAt.atZoneSameInstant(zoneId).toLocalDate());
        transaksi.setJenis(schedule.getJenis());
        transaksi.setKategori(schedule.getKategori());
        transaksi.setSumberDana(schedule.getSumberDana());
        transaksi.setNominal(schedule.getNominal());

        String baseNote = schedule.getKeterangan() == null ? "" : schedule.getKeterangan().trim();
        String generatedNote = "Schedule: " + schedule.getTitle();
        transaksi.setKeterangan(baseNote.isBlank() ? generatedNote : baseNote + " (" + generatedNote + ")");

        transaksiRepository.save(transaksi);
    }

    private OffsetDateTime atScheduledTime(ScheduledTransaction schedule, LocalDate date, ZoneId zoneId) {
        LocalTime timeOfDay = schedule.getTimeOfDay() != null ? schedule.getTimeOfDay() : LocalTime.of(8, 0);
        return date.atTime(timeOfDay).atZone(zoneId).toOffsetDateTime();
    }

    private OffsetDateTime nextWeeklyOnOrAfter(ScheduledTransaction schedule, LocalDate startDate, Integer targetDay, ZoneId zoneId) {
        int current = startDate.getDayOfWeek().getValue();
        int addDays = (DayOfWeek.of(targetDay).getValue() - current + 7) % 7;
        return atScheduledTime(schedule, startDate.plusDays(addDays), zoneId);
    }

    private OffsetDateTime monthlyAt(ScheduledTransaction schedule, int year, int month, Integer preferredDay, ZoneId zoneId) {
        YearMonth yearMonth = YearMonth.of(year, month);
        int day = Math.min(preferredDay, yearMonth.lengthOfMonth());
        return atScheduledTime(schedule, yearMonth.atDay(day), zoneId);
    }

    private OffsetDateTime addMonths(ScheduledTransaction schedule, OffsetDateTime previousRun) {
        ZoneId zoneId = ZoneId.of(schedule.getTimezone());
        LocalDate previousDate = previousRun.atZoneSameInstant(zoneId).toLocalDate();
        YearMonth target = YearMonth.from(previousDate).plusMonths(schedule.getInterval());
        return monthlyAt(schedule, target.getYear(), target.getMonthValue(), schedule.getDayOfMonth(), zoneId);
    }
}
