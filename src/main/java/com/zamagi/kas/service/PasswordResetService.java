package com.zamagi.kas.service;

import com.zamagi.kas.model.User;
import com.zamagi.kas.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class PasswordResetService {

    public record PasswordResetResult(boolean success, String code, String message) {

    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // Reset token valid selama 15 menit
    private static final int RESET_TOKEN_EXPIRY_MINUTES = 15;

    // Rate limiting: maksimal 3 request per jam per user
    private static final int MAX_FORGOT_PASSWORD_REQUESTS_PER_HOUR = 3;
    private static final int FORGOT_PASSWORD_RATE_LIMIT_HOURS = 1;
    private static final String GENERIC_FORGOT_PASSWORD_MESSAGE
            = "Jika username terdaftar, link reset password akan dikirim ke email Anda";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Generate reset token dan kirim email ke user
     *
     * @param username Username pengguna yang ingin reset password
     * @param frontendBaseUrl Base URL frontend untuk reset password link
     * @return Pesan sukses atau error
     */
    public PasswordResetResult generateResetToken(String username, String frontendBaseUrl) {
        // Validasi input
        if (username == null || username.trim().isEmpty()) {
            return new PasswordResetResult(false, "USERNAME_REQUIRED", "Username tidak boleh kosong");
        }

        if (frontendBaseUrl == null || frontendBaseUrl.trim().isEmpty()) {
            return new PasswordResetResult(false, "FRONTEND_URL_INVALID", "URL frontend tidak valid");
        }

        Optional<User> userOptional = userRepository.findByUsername(username.trim());

        if (userOptional.isEmpty()) {
            // Return generic message untuk security (prevent email enumeration)
            return new PasswordResetResult(true, "RESET_EMAIL_SENT", GENERIC_FORGOT_PASSWORD_MESSAGE);
        }

        User user = userOptional.get();

        // ── RATE LIMITING CHECK ──────────────────────────────────────────────
        if (!isRateLimitPassed(user)) {
            return new PasswordResetResult(true, "RESET_EMAIL_SENT", GENERIC_FORGOT_PASSWORD_MESSAGE);
        }

        // Jika user login via GOOGLE saja, tidak bisa reset password
        if ("GOOGLE".equalsIgnoreCase(user.getAuthProvider())) {
            return new PasswordResetResult(true, "RESET_EMAIL_SENT", GENERIC_FORGOT_PASSWORD_MESSAGE);
        }

        // Jika email tidak ada, user tidak bisa reset password
        if (user.getEmail() == null || user.getEmail().trim().isBlank()) {
            return new PasswordResetResult(true, "RESET_EMAIL_SENT", GENERIC_FORGOT_PASSWORD_MESSAGE);
        }

        try {
            String resetToken = generateSecureToken();
            String resetTokenHash = hashResetToken(resetToken);
            LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(RESET_TOKEN_EXPIRY_MINUTES);

            // Simpan token dan expiry di database
            user.setPasswordResetToken(resetTokenHash);
            user.setPasswordResetExpiresAt(expiresAt);

            // Update rate limiting counter
            updateRateLimiting(user);

            userRepository.save(user);

            // Buat reset password link
            String resetLink = frontendBaseUrl.replaceAll("/$", "") + "/reset-password?token=" + resetToken;

            // Kirim email
            sendResetEmail(user.getEmail().trim(), user.getNamaLengkap(), resetLink);

            return new PasswordResetResult(true, "RESET_EMAIL_SENT", GENERIC_FORGOT_PASSWORD_MESSAGE);
        } catch (Exception e) {
            System.err.println("Error saat mengirim reset email: " + e.getMessage());

            e.printStackTrace();

            // Clear token jika gagal kirim email
            try {
                user.setPasswordResetToken(null);
                user.setPasswordResetExpiresAt(null);
                userRepository.save(user);
            } catch (Exception cleanupError) {
                System.err.println("Error saat cleanup token: " + cleanupError.getMessage());
            }
            return new PasswordResetResult(false, "RESET_EMAIL_FAILED",
                    "Terjadi kesalahan saat memproses permintaan Anda. Silakan coba lagi.");
        }
    }

    /**
     * Validasi dan update password menggunakan reset token
     *
     * @param resetToken Token dari URL parameter
     * @param newPassword Password baru
     * @return Pesan sukses atau error
     */
    public PasswordResetResult resetPassword(String resetToken, String newPassword) {
        // Validasi input
        if (resetToken == null || resetToken.trim().isEmpty()) {
            return new PasswordResetResult(false, "TOKEN_INVALID", "Token reset password tidak valid");
        }

        // Validasi format password
        if (newPassword == null || newPassword.length() < 8) {
            return new PasswordResetResult(false, "WEAK_PASSWORD", "Password minimal 8 karakter");
        }
        if (newPassword.length() > 100) {
            return new PasswordResetResult(false, "WEAK_PASSWORD", "Password maksimal 100 karakter");
        }
        if (!newPassword.matches(".*[a-zA-Z].*") || !newPassword.matches(".*[0-9].*")) {
            return new PasswordResetResult(false, "WEAK_PASSWORD", "Password harus mengandung huruf dan angka");
        }

        // Cari user dengan reset token menggunakan repository method
        Optional<User> userOptional = findUserByResetToken(resetToken.trim());

        if (userOptional.isEmpty()) {
            return new PasswordResetResult(false, "TOKEN_INVALID", "Token tidak valid atau sudah kadaluarsa");
        }

        User user = userOptional.get();

        // Cek apakah token sudah expired
        if (user.getPasswordResetExpiresAt() == null
                || LocalDateTime.now().isAfter(user.getPasswordResetExpiresAt())) {

            // Clear token yang sudah expired
            user.setPasswordResetToken(null);
            user.setPasswordResetExpiresAt(null);
            userRepository.save(user);

            return new PasswordResetResult(false, "TOKEN_EXPIRED",
                    "Token sudah kadaluarsa. Silakan request reset password lagi.");
        }

        try {
            // Update password dan clear token
            user.setPassword(passwordEncoder.encode(newPassword));
            user.setPasswordResetToken(null);
            user.setPasswordResetExpiresAt(null);
            user.incrementTokenVersion();
            userRepository.save(user);

            return new PasswordResetResult(true, "PASSWORD_RESET",
                    "Password berhasil direset. Silakan login dengan password baru.");
        } catch (Exception e) {
            System.err.println("Error saat reset password: " + e.getMessage());
            return new PasswordResetResult(false, "RESET_FAILED",
                    "Terjadi kesalahan saat mereset password. Silakan coba lagi.");
        }
    }

    private String generateSecureToken() {
        byte[] tokenBytes = new byte[32];
        SECURE_RANDOM.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    private String hashResetToken(String resetToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(resetToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Gagal membuat hash reset token", e);
        }
    }

    private Optional<User> findUserByResetToken(String resetToken) {
        String resetTokenHash = hashResetToken(resetToken);
        Optional<User> userOptional = userRepository.findByPasswordResetToken(resetTokenHash);
        if (userOptional.isPresent()) {
            return userOptional;
        }
        // Backward-compatible untuk token lama yang sudah terlanjur tersimpan mentah.
        return userRepository.findByPasswordResetToken(resetToken);
    }

    /**
     * Kirim email reset password ke user
     */
    private void sendResetEmail(String email, String namaLengkap, String resetLink) {
        if (email == null || email.trim().isEmpty()) {
            throw new IllegalArgumentException("Email tidak boleh kosong");
        }
        if (namaLengkap == null) {
            namaLengkap = "User"; // Fallback jika nama kosong
        }
        if (resetLink == null || resetLink.trim().isEmpty()) {
            throw new IllegalArgumentException("Reset link tidak boleh kosong");
        }

        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            String htmlContent = buildResetPasswordEmailHtml(namaLengkap, resetLink);
            String plainTextContent = buildResetPasswordEmailText(namaLengkap, resetLink);

            helper.setTo(email.trim());
            helper.setFrom("noreply@zonakas.com");
            helper.setSubject("Reset Password - ZonaKas");
            helper.setText(plainTextContent, htmlContent); // Plain text + HTML

            mailSender.send(mimeMessage);
        } catch (Exception e) {
            System.err.println("Error saat mengirim email ke " + email + ": " + e.getMessage());
            throw new RuntimeException("Gagal mengirim email reset password", e);
        }
    }

    /**
     * Cek apakah user sudah melewati batas rate limiting
     *
     * @param user User yang akan dicek
     * @return true jika boleh request, false jika sudah melewati limit
     */
    private boolean isRateLimitPassed(User user) {
        LocalDateTime lastRequest = user.getLastForgotPasswordRequest();
        Integer requestCount = user.getForgotPasswordRequestCount();

        if (lastRequest == null || requestCount == null) {
            // Belum pernah request, boleh lanjut
            return true;
        }

        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(FORGOT_PASSWORD_RATE_LIMIT_HOURS);

        if (lastRequest.isBefore(oneHourAgo)) {
            // Sudah lebih dari 1 jam, reset counter
            user.setForgotPasswordRequestCount(0);
            return true;
        }

        // Cek apakah sudah melewati limit
        return requestCount < MAX_FORGOT_PASSWORD_REQUESTS_PER_HOUR;
    }

    /**
     * Update rate limiting counter setelah berhasil request
     */
    private void updateRateLimiting(User user) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lastRequest = user.getLastForgotPasswordRequest();
        Integer currentCount = user.getForgotPasswordRequestCount();

        if (lastRequest == null || currentCount == null) {
            // First request
            user.setLastForgotPasswordRequest(now);
            user.setForgotPasswordRequestCount(1);
        } else {
            LocalDateTime oneHourAgo = now.minusHours(FORGOT_PASSWORD_RATE_LIMIT_HOURS);

            if (lastRequest.isBefore(oneHourAgo)) {
                // Reset counter karena sudah lebih dari 1 jam
                user.setForgotPasswordRequestCount(1);
                user.setLastForgotPasswordRequest(now);
            } else {
                // Increment counter
                user.setForgotPasswordRequestCount(currentCount + 1);
                user.setLastForgotPasswordRequest(now);
            }
        }
    }

    private String buildResetPasswordEmailText(String namaLengkap, String resetLink) {
        return String.format("""
            Halo %s,

            Anda meminta untuk reset password akun ZonaKas Anda.

            Klik link di bawah untuk reset password (link valid 15 menit):
            %s

            PENTING:
            - Link ini akan kadaluarsa dalam 15 menit
            - Maksimal 3 kali permintaan reset password per jam

            Jika Anda tidak meminta ini, abaikan email ini.

            Salam,
            Tim ZonaKas

            ---
            Email ini dikirim secara otomatis. Mohon tidak membalas email ini.
            Butuh bantuan? Hubungi support@zonakas.com
            """, namaLengkap, resetLink);
    }

    private String buildResetPasswordEmailHtml(String namaLengkap, String resetLink) {
        return String.format("""
            <!DOCTYPE html>
            <html lang="id">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Reset Password - ZonaKas</title>
                <style>
                    body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; margin: 0; padding: 0; background-color: #f8fafc; }
                    .container { max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 12px; overflow: hidden; box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1); }
                    /* PERHATIKAN: 0% dan 100% diubah menjadi 0%% dan 100%% agar tidak bentrok dengan String.format */
                    .header { background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%); color: white; padding: 40px 30px; text-align: center; }
                    .header h1 { margin: 0; font-size: 28px; font-weight: 700; }
                    .header p { margin: 10px 0 0 0; opacity: 0.9; font-size: 16px; }
                    .content { padding: 40px 30px; color: #374151; line-height: 1.6; }
                    .content h2 { color: #1f2937; margin-top: 0; font-size: 24px; }
                    /* PERHATIKAN: 0%% dan 100%% di sini juga */
                    .reset-button { display: inline-block; background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%); color: white; text-decoration: none; padding: 16px 32px; border-radius: 8px; font-weight: 600; font-size: 16px; margin: 20px 0; box-shadow: 0 4px 14px rgba(102, 126, 234, 0.4); }
                    .reset-button:hover { transform: translateY(-2px); box-shadow: 0 6px 20px rgba(102, 126, 234, 0.6); }
                    .warning { background-color: #fef3c7; border-left: 4px solid #f59e0b; padding: 16px; margin: 20px 0; border-radius: 6px; }
                    .warning h3 { margin: 0 0 8px 0; color: #92400e; font-size: 16px; }
                    .warning p { margin: 0; color: #78350f; font-size: 14px; }
                    .footer { background-color: #f9fafb; padding: 30px; text-align: center; border-top: 1px solid #e5e7eb; }
                    .footer p { margin: 0; color: #6b7280; font-size: 14px; }
                    .footer a { color: #667eea; text-decoration: none; font-weight: 500; }
                    .footer a:hover { text-decoration: underline; }
                    .logo { font-size: 32px; font-weight: bold; color: white; margin-bottom: 10px; }
                    @media (max-width: 600px) {
                        .container { margin: 10px; }
                        .header, .content, .footer { padding: 20px; }
                        .header h1 { font-size: 24px; }
                        .content h2 { font-size: 20px; }
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <div class="logo">💰 ZonaKas</div>
                        <h1>Reset Password</h1>
                        <p>Permintaan reset password telah diterima</p>
                    </div>
                    
                    <div class="content">
                        <h2>Halo %s!</h2>
                        
                        <p>Kami menerima permintaan untuk mereset password akun ZonaKas Anda. Jika ini memang Anda yang meminta, klik tombol di bawah untuk membuat password baru:</p>
                        
                        <div style="text-align: center;">
                            <a href="%s" class="reset-button">Reset Password Sekarang</a>
                        </div>
                        
                        <p style="margin-top: 30px;">Atau salin dan tempel link berikut ke browser Anda:</p>
                        <p style="word-break: break-all; background-color: #f3f4f6; padding: 12px; border-radius: 6px; font-family: monospace; font-size: 14px; color: #374151;">%s</p>
                        
                        <div class="warning">
                            <h3>⚠️ Link ini akan kadaluarsa</h3>
                            <p>Link reset password ini hanya valid selama <strong>15 menit</strong> untuk alasan keamanan. Jika link sudah kadaluarsa, silakan request reset password lagi.</p>
                            <p style="margin-top: 8px; font-size: 13px;"><strong>Batas permintaan:</strong> Maksimal 3 kali permintaan per jam untuk mencegah spam.</p>
                        </div>
                        
                        <p><strong>Tidak meminta reset password?</strong><br>
                        Jika Anda tidak meminta perubahan password, abaikan email ini. Akun Anda tetap aman.</p>
                        
                        <p style="margin-top: 30px;">Salam hangat,<br>
                        <strong>Tim ZonaKas</strong></p>
                    </div>
                    
                    <div class="footer">
                        <p>Butuh bantuan? <a href="mailto:support@zonakas.com">Hubungi Support</a></p>
                        <p style="margin-top: 10px; font-size: 12px; color: #9ca3af;">
                            Email ini dikirim secara otomatis. Mohon tidak membalas email ini.
                        </p>
                    </div>
                </div>
            </body>
            </html>
            """, namaLengkap, resetLink, resetLink);
    }

    /**
     * Validasi apakah reset token masih valid (belum expired)
     *
     * @param resetToken Token yang akan divalidasi
     * @return true jika token valid, false jika tidak valid/expired
     */
    public boolean isResetTokenValid(String resetToken) {
        if (resetToken == null || resetToken.trim().isEmpty()) {
            return false;
        }

        Optional<User> userOptional = findUserByResetToken(resetToken.trim());

        if (userOptional.isEmpty()) {
            return false;
        }

        User user = userOptional.get();

        // Cek apakah token sudah expired
        if (user.getPasswordResetExpiresAt() == null
                || LocalDateTime.now().isAfter(user.getPasswordResetExpiresAt())) {

            // Clear token yang sudah expired
            user.setPasswordResetToken(null);
            user.setPasswordResetExpiresAt(null);
            userRepository.save(user);

            return false;
        }

        return true;
    }

    /**
     * Membersihkan semua token yang sudah kadaluarsa di database. Dapat
     * dipanggil melalui Scheduler (Cron Job).
     *
     * @return Jumlah token yang berhasil dihapus
     */
    public int cleanupExpiredTokens() {
        try {
            // Cari semua user yang punya token expired
            List<User> usersWithExpiredTokens = userRepository.findAll().stream()
                    .filter(user -> user.getPasswordResetToken() != null
                    && user.getPasswordResetExpiresAt() != null
                    && LocalDateTime.now().isAfter(user.getPasswordResetExpiresAt()))
                    .collect(Collectors.toList());

            // Clear token expired
            for (User user : usersWithExpiredTokens) {
                user.setPasswordResetToken(null);
                user.setPasswordResetExpiresAt(null);
            }

            if (!usersWithExpiredTokens.isEmpty()) {
                userRepository.saveAll(usersWithExpiredTokens);
            }

            return usersWithExpiredTokens.size();
        } catch (Exception e) {
            System.err.println("Error saat cleanup expired tokens: " + e.getMessage());
            return 0;
        }
    }
}
