package com.zamagi.kas.controller;

import com.zamagi.kas.model.User;
import com.zamagi.kas.repository.UserRepository;
import com.zamagi.kas.security.JwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api/user")
@CrossOrigin(origins = "*")
public class UserPreferencesController {

    private static final long MAX_AVATAR_SIZE = 5 * 1024 * 1024; // 5 MB

    @Value("${app.upload.avatar-dir:uploads/avatars}")
    private String avatarUploadDirRaw;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private JwtUtils jwtUtils;

    private User getCurrentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User tidak ditemukan"));
    }

    private Path getAvatarUploadDir() {
        return Paths.get(avatarUploadDirRaw).toAbsolutePath().normalize();
    }

    private String parseJwt(HttpServletRequest request) {
        String headerAuth = request.getHeader("Authorization");
        if (headerAuth != null && headerAuth.startsWith("Bearer ")) {
            return headerAuth.substring(7);
        }
        return null;
    }

    private String getLoginProvider(HttpServletRequest request, User user) {
        String token = parseJwt(request);
        if (token != null) {
            try {
                String loginProvider = jwtUtils.getLoginProviderFromToken(token);
                if (loginProvider != null && !loginProvider.isBlank()) {
                    return loginProvider;
                }
            } catch (Exception ignored) {
                // Token sudah divalidasi oleh security filter; fallback dipakai untuk token lama.
            }
        }
        return user.getAuthProvider();
    }

    // ── GANTI PASSWORD ────────────────────────────────────────────────────────
    // PUT /api/user/password
    // Body: { "passwordLama": "abc123", "passwordBaru": "xyz789AB", "konfirmasi": "xyz789AB" }
    @PutMapping("/password")
    public ResponseEntity<?> gantiPassword(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String passwordLama = body.get("passwordLama");
        String passwordBaru = body.get("passwordBaru");
        String konfirmasi = body.get("konfirmasi");
        User user = getCurrentUser();
        String loginProvider = getLoginProvider(request, user);
        boolean isLocalLogin = "LOCAL".equalsIgnoreCase(loginProvider);

        // ── Validasi input ──────────────────────────────────────────────────
        if (isLocalLogin && (passwordLama == null || passwordLama.isBlank())) {
            return ResponseEntity.badRequest().body("Password lama wajib diisi");
        }

        if (passwordBaru == null || passwordBaru.isBlank()) {
            return ResponseEntity.badRequest().body("Password baru wajib diisi");
        }

        if (passwordBaru.length() < 8) {
            return ResponseEntity.badRequest().body("Password baru minimal 8 karakter");
        }

        if (passwordBaru.length() > 100) {
            return ResponseEntity.badRequest().body("Password baru maksimal 100 karakter");
        }

        if (!passwordBaru.matches(".*[a-zA-Z].*") || !passwordBaru.matches(".*[0-9].*")) {
            return ResponseEntity.badRequest().body("Password baru harus mengandung huruf dan angka");
        }

        if (!passwordBaru.equals(konfirmasi)) {
            return ResponseEntity.badRequest().body("Konfirmasi password tidak cocok");
        }

        if (isLocalLogin && passwordLama.equals(passwordBaru)) {
            return ResponseEntity.badRequest().body("Password baru tidak boleh sama dengan password lama");
        }

        // ── Verifikasi password lama ────────────────────────────────────────
        if (isLocalLogin && !encoder.matches(passwordLama, user.getPassword())) {
            return ResponseEntity.status(401).body("Password lama salah");
        }

        // ── Simpan password baru ────────────────────────────────────────────
        user.setPassword(encoder.encode(passwordBaru));
        if (!isLocalLogin && !"HYBRID".equalsIgnoreCase(user.getAuthProvider())) {
            user.setAuthProvider("HYBRID");
        }
        userRepository.save(user);

        // Kembalikan token baru agar user tidak perlu login ulang
        // (token lama masih valid sampai expire, tapi setidaknya kita beri yang baru)
        String newAccessToken = jwtUtils.generateAccessToken(user.getUsername(), loginProvider);
        String newRefreshToken = jwtUtils.generateRefreshToken(user.getUsername(), loginProvider);

        return ResponseEntity.ok(Map.of(
                "message", isLocalLogin ? "Password berhasil diubah" : "Password lokal berhasil dibuat",
                "token", newAccessToken,
                "refreshToken", newRefreshToken,
                "authProvider", loginProvider,
                "accountAuthProvider", user.getAuthProvider()
        ));
    }

    // ── PREFERENCES: DOMPET HARIAN ────────────────────────────────────────────
    @GetMapping("/preferences")
    public ResponseEntity<?> getPreferences() {
        User user = getCurrentUser();
        List<String> dompetList = parseDompetHarian(user.getDompetHarian());
        return ResponseEntity.ok(Map.of("dompetHarian", dompetList));
    }

    @PutMapping("/preferences")
    public ResponseEntity<?> updatePreferences(@RequestBody Map<String, List<String>> body) {
        try {
            if (body == null) {
                return ResponseEntity.badRequest().body("Body request wajib ada");
            }

            List<String> dompetList = body.get("dompetHarian");
            if (dompetList == null) {
                return ResponseEntity.badRequest().body("Field 'dompetHarian' wajib ada");
            }

            for (String item : dompetList) {
                if (item == null || item.isBlank()) {
                    return ResponseEntity.badRequest().body("Nama aset tidak boleh kosong");
                }
                if (item.length() > 100) {
                    return ResponseEntity.badRequest().body("Nama aset maksimal 100 karakter");
                }
            }

            User user = getCurrentUser();
            String stored = dompetList.stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.joining("||"));

            user.setDompetHarian(stored);
            userRepository.save(user);

            return ResponseEntity.ok(Map.of(
                    "message", "Dompet harian berhasil disimpan",
                    "dompetHarian", dompetList
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Gagal menyimpan preferences: " + e.getMessage());
        }
    }

    private List<String> parseDompetHarian(String value) {
        if (value == null || value.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(value.split("\\|\\|"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    // 1. GET: Mengirimkan data profil ke frontend saat halaman Settings dimuat
    @GetMapping("/profile")
    public ResponseEntity<?> getUserProfile(Authentication authentication) {
        // Ambil username dari token JWT yang sedang login
        String username = authentication.getName();

        // Cari user di database
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("Error: User tidak ditemukan."));

        // Bungkus data ke dalam Map/JSON untuk dikirim ke frontend
        Map<String, String> profil = new HashMap<>();
        profil.put("username", user.getUsername());
        profil.put("namaLengkap", user.getNamaLengkap());
        profil.put("email", user.getEmail());
        profil.put("nomorHp", user.getNomorHp());
        profil.put("terimaLaporanBulanan", String.valueOf(user.getTerimaLaporanBulanan()));
        profil.put("authProvider", user.getAuthProvider() == null ? "LOCAL" : user.getAuthProvider());
        if (user.getAvatarPath() != null && !user.getAvatarPath().isBlank()) {
            profil.put("avatarUrl", ServletUriComponentsBuilder.fromCurrentContextPath()
                    .path("/avatars/")
                    .path(user.getAvatarPath())
                    .toUriString());
        } else {
            profil.put("avatarUrl", "");
        }

        return ResponseEntity.ok(profil);
    }

    @PutMapping(value = "/profile/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadProfilePhoto(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "File foto profil wajib dipilih"));
        }

        if (file.getSize() > MAX_AVATAR_SIZE) {
            return ResponseEntity.badRequest().body(Map.of("message", "Ukuran file maksimal 5MB"));
        }

        String originalFilename = StringUtils.cleanPath(file.getOriginalFilename());
        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex < 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "Format file tidak dikenali"));
        }

        String extension = originalFilename.substring(dotIndex + 1).toLowerCase();
        if (!List.of("png", "jpg", "jpeg", "webp", "gif").contains(extension)) {
            return ResponseEntity.badRequest().body(Map.of("message", "Hanya format gambar PNG, JPG, JPEG, WEBP, atau GIF yang diperbolehkan"));
        }

        User user = getCurrentUser();
        try {
            Path avatarUploadDir = getAvatarUploadDir();
            System.out.println("[ProfilePhoto] Avatar upload dir=" + avatarUploadDir);
            Files.createDirectories(avatarUploadDir);

            String avatarFilename = "avatar_" + user.getUsername() + "_" + System.currentTimeMillis() + "." + extension;
            Path targetPath = avatarUploadDir.resolve(avatarFilename).normalize();
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            if (user.getAvatarPath() != null && !user.getAvatarPath().isBlank()) {
                Path oldPath = avatarUploadDir.resolve(user.getAvatarPath()).normalize();
                if (Files.exists(oldPath)) {
                    Files.delete(oldPath);
                }
            }

            user.setAvatarPath(avatarFilename);
            userRepository.save(user);

            String avatarUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                    .path("/avatars/")
                    .path(avatarFilename)
                    .toUriString();

            return ResponseEntity.ok(Map.of("message", "Foto profil berhasil disimpan", "avatarUrl", avatarUrl));
        } catch (Exception e) {
            System.err.println("[ProfilePhoto] Gagal menyimpan foto profil ke dir=" + getAvatarUploadDir()
                    + ": " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("message", "Gagal menyimpan foto profil: " + e.getMessage()));
        }
    }

    // 2. PUT: Menyimpan perubahan data profil yang diedit dari frontend
    @PutMapping("/profile")
    public ResponseEntity<?> updateUserProfile(@RequestBody Map<String, String> req) {
        User user = getCurrentUser();

        // 1. Validasi Nama Lengkap
        if (req.containsKey("namaLengkap")) {
            String nama = req.get("namaLengkap");
            if (nama == null || nama.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message", "Nama lengkap tidak boleh kosong"));
            }
            if (nama.length() > 100) {
                return ResponseEntity.badRequest().body(Map.of("message", "Nama lengkap maksimal 100 karakter"));
            }
            user.setNamaLengkap(nama.trim());
        }

        // 2. Validasi Email
        if (req.containsKey("email")) {
            String email = req.get("email");
            if (email != null && !email.trim().isEmpty()) {
                String emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$";
                if (!email.matches(emailRegex)) {
                    return ResponseEntity.badRequest().body(Map.of("message", "Format email tidak valid"));
                }
                user.setEmail(email.trim().toLowerCase());
            } else {
                user.setEmail("");
            }
        }

        // 3. Validasi Nomor HP
        if (req.containsKey("nomorHp")) {
            String nomorHp = req.get("nomorHp");
            if (nomorHp != null && !nomorHp.trim().isEmpty()) {
                String phoneRegex = "^(\\+62|62|0)[0-9]{9,13}$";
                if (!nomorHp.matches(phoneRegex)) {
                    return ResponseEntity.badRequest().body(Map.of("message", "Format nomor HP tidak valid (harus angka, 10-14 digit)"));
                }
                user.setNomorHp(nomorHp.trim());
            } else {
                user.setNomorHp("");
            }
        }

        if (req.containsKey("terimaLaporanBulanan")) {
            String terimaLaporanStr = req.get("terimaLaporanBulanan");
            if (terimaLaporanStr != null) {
                user.setTerimaLaporanBulanan(Boolean.parseBoolean(terimaLaporanStr));
            }
        }

        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "Profil berhasil diupdate"));
    }
}
