package com.zamagi.kas.service;

import com.zamagi.kas.dto.LaporanSummary;
import com.zamagi.kas.model.User;
import com.zamagi.kas.repository.TransaksiRepository;
import com.zamagi.kas.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.scheduling.annotation.Async;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class ReportScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReportScheduler.class);

    private static final String EMAIL_PATTERN = "^[A-Za-z0-9+_.-]+@(.+)$";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private PdfReportService pdfReportService;

    // Cron: Detik Menit Jam Hari-dalam-Bulan Bulan Hari-dalam-Minggu
    // "0 0 7 1 * *" berarti setiap tanggal 1 jam 07:00 pagi
    @Scheduled(cron = "0 0 7 1 * *")
    public void sendMonthlyReports() {
        List<User> users = userRepository.findUsersForMonthlyReport();
        LOGGER.info("Memulai pengiriman laporan bulanan untuk {} user.", users.size());

        int countSukses = 0;
        int countGagal = 0;

        for (User user : users) {
            // 1. Validasi keberadaan email
            if (user.getEmail() == null || user.getEmail().isBlank()) {
                LOGGER.warn("User {} dilewati: Email kosong.", user.getUsername());
                countGagal++;
                continue;
            }

            // 2. Validasi format email dengan Regex
            if (!Pattern.matches(EMAIL_PATTERN, user.getEmail())) {
                LOGGER.warn("User {} dilewati: Format email tidak valid ({}).", user.getUsername(), user.getEmail());
                countGagal++;
                continue;
            }

            sendEmailAsync(user);
            countSukses++;
        }

        LOGGER.info("Pengiriman selesai. {} antrean diproses, {} dilewati.", countSukses, countGagal);
    }

    private void sendEmail(User user) {
        try {
            LocalDate lastMonth = LocalDate.now().minusMonths(1);
            String bulan = lastMonth.getYear() + "-" + String.format("%02d", lastMonth.getMonthValue());

            // Generate PDF
            byte[] pdfBytes = pdfReportService.generateLaporanBulananPdf(user, bulan);

            // Ambil summary
            LaporanSummary summary = pdfReportService.getSummary(user, bulan);
            long totalMasuk = summary.getTotalMasuk();
            long totalKeluar = summary.getTotalKeluar();

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            String namaBulan = lastMonth.getMonth()
                    .getDisplayName(java.time.format.TextStyle.FULL, new Locale("id", "ID"));

            helper.setTo(user.getEmail());
            helper.setSubject("Laporan Bulanan - " + namaBulan);

            String htmlContent = buildHtmlEmail(user, namaBulan, totalMasuk, totalKeluar);
            helper.setText(htmlContent, true);
            helper.setFrom("Zonakas <no-reply@kas-apps.rotibuayajkt.web.id>");
            helper.addAttachment(
                    "Laporan_" + user.getUsername() + "_" + bulan + ".pdf",
                    () -> new java.io.ByteArrayInputStream(pdfBytes)
            );

            mailSender.send(mimeMessage);
            LOGGER.debug("Email sukses dikirim ke {}", user.getEmail());
        } catch (Exception e) {
            LOGGER.error("Gagal mengirim email ke user {}: {}", user.getUsername(), e.getMessage());
        }
    }

    private String buildHtmlEmail(User user, String namaBulan, long masuk, long keluar) {
        return """
    <div style="font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; color: #333333; padding: 20px; line-height: 1.6; max-width: 600px; margin: 0 auto; border: 1px solid #e0e0e0; border-radius: 8px;">
        <h2 style="color: #2c3e50; border-bottom: 2px solid #3498db; padding-bottom: 10px;">Laporan Keuangan Bulanan - Zonakas</h2>

        <p>Halo <b>%s</b>,</p>

        <p>Berikut adalah ringkasan aktivitas keuangan Anda untuk periode <b>%s</b>:</p>

        <div style="background-color: #f8f9fa; padding: 20px; border-radius: 8px; margin: 20px 0; border-left: 4px solid #3498db;">
            <table style="width: 100%%; border-collapse: collapse;">
                <tr>
                    <td style="padding: 5px 0; color: #555;">Total Pemasukan:</td>
                    <td style="padding: 5px 0; text-align: right; font-weight: bold; color: #27ae60;">Rp %,d</td>
                </tr>
                <tr>
                    <td style="padding: 5px 0; color: #555;">Total Pengeluaran:</td>
                    <td style="padding: 5px 0; text-align: right; font-weight: bold; color: #c0392b;">Rp %,d</td>
                </tr>
            </table>
        </div>

        <p style="margin-top: 20px;">
            Rincian transaksi selengkapnya telah kami sertakan pada dokumen terlampir untuk referensi Anda.
        </p>

        <p style="margin-top: 30px;">
            Salam hangat,<br/>
            <b>Tim Zonakas</b>
        </p>

        <hr style="border: none; border-top: 1px solid #eeeeee; margin-top: 30px; margin-bottom: 20px;" />

        <p style="font-size: 11px; color: #999999; text-align: center;">
            Email ini dibuat secara otomatis oleh sistem Zonakas. Mohon untuk tidak membalas email ini.<br/>
            Terima kasih telah mempercayakan Zonakas sebagai mitra pencatatan keuangan Anda.
        </p>
    </div>
    """.formatted(
                user.getNamaLengkap(),
                namaBulan,
                masuk,
                keluar
        );
    }

    @Async
    public void sendEmailAsync(User user) {
        sendEmail(user);
    }
}
