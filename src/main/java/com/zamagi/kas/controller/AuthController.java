package com.zamagi.kas.controller;

import com.zamagi.kas.model.User;
import com.zamagi.kas.repository.UserRepository;
import com.zamagi.kas.security.JwtUtils;
import com.zamagi.kas.service.FirebaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    JwtUtils jwtUtils;

    @Autowired
    private FirebaseService firebaseService;

    // ── VALIDASI ─────────────────────────────────────────────────────────────
    private String validasiUser(String username, String password) {
        if (username == null || username.isBlank()) {
            return "Username tidak boleh kosong";
        }
        if (username.length() < 3) {
            return "Username minimal 3 karakter";
        }
        if (username.length() > 50) {
            return "Username maksimal 50 karakter";
        }
        if (!username.matches("^[a-zA-Z0-9_.]+$")) {
            return "Username hanya boleh huruf, angka, titik, dan underscore";
        }
        if (password == null || password.isBlank()) {
            return "Password tidak boleh kosong";
        }
        if (password.length() < 8) {
            return "Password minimal 8 karakter";
        }
        if (password.length() > 100) {
            return "Password maksimal 100 karakter";
        }
        // Validasi kekuatan password: harus ada huruf dan angka
        if (!password.matches(".*[a-zA-Z].*") || !password.matches(".*[0-9].*")) {
            return "Password harus mengandung huruf dan angka";
        }
        return null;
    }

    // ── REGISTER ─────────────────────────────────────────────────────────────
    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody User user) {
        // Validasi bawaan untuk username & password
        String error = validasiUser(user.getUsername(), user.getPassword());
        if (error != null) {
            return ResponseEntity.badRequest().body(error);
        }

        // 👇 Tambahan validasi untuk Nama Lengkap dan Email
        if (user.getNamaLengkap() == null || user.getNamaLengkap().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Nama Lengkap tidak boleh kosong!");
        }
        if (user.getEmail() == null || !user.getEmail().contains("@")) {
            return ResponseEntity.badRequest().body("Email tidak valid!");
        }

        // Cek apakah username sudah ada
        if (userRepository.existsByUsername(user.getUsername())) {
            return ResponseEntity.badRequest().body("Username sudah terdaftar!");
        }

        // Enkripsi password
        user.setPassword(encoder.encode(user.getPassword()));

        // Simpan ke database
        // (namaLengkap dan email otomatis ikut tersimpan karena sudah ada di dalam object 'user')
        userRepository.save(user);

        return ResponseEntity.ok("User berhasil didaftarkan!");
    }

    // ── LOGIN ─────────────────────────────────────────────────────────────────
    // Sekarang return BOTH access token (15 menit) + refresh token (7 hari)
    @PostMapping("/login")
    public ResponseEntity<?> authenticateUser(@RequestBody User user) {
        if (user.getUsername() == null || user.getUsername().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username wajib diisi"));
        }
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password wajib diisi"));
        }

        var userOptional = userRepository.findByUsername(user.getUsername());
        if (userOptional.isPresent()) {
            User u = userOptional.get();
            if (encoder.matches(user.getPassword(), u.getPassword())) {
                String accessToken = jwtUtils.generateAccessToken(u.getUsername());
                String refreshToken = jwtUtils.generateRefreshToken(u.getUsername());
                return ResponseEntity.ok(Map.of(
                        "token", accessToken, // nama "token" tetap sama agar frontend tidak perlu banyak ubah
                        "refreshToken", refreshToken,
                        "username", u.getUsername()
                ));
            }
        }

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Username atau Password salah!"));
    }

    // ── REFRESH TOKEN ─────────────────────────────────────────────────────────
    // POST /api/auth/refresh
    // Body: { "refreshToken": "eyJ..." }
    // Return: { "token": "eyJ...", "refreshToken": "eyJ..." }
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");

        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "refreshToken wajib diisi"));
        }

        // Validasi: harus valid DAN bertipe "refresh"
        if (!jwtUtils.validateRefreshToken(refreshToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Refresh token tidak valid atau sudah expired"));
        }

        String username = jwtUtils.getUserNameFromJwtToken(refreshToken);

        // Pastikan user masih ada di database
        if (!userRepository.existsByUsername(username)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "User tidak ditemukan"));
        }

        // Issue token baru
        String newAccessToken = jwtUtils.generateAccessToken(username);
        String newRefreshToken = jwtUtils.generateRefreshToken(username);

        return ResponseEntity.ok(Map.of(
                "token", newAccessToken,
                "refreshToken", newRefreshToken,
                "username", username
        ));
    }

    // ── LOGIN DENGAN GOOGLE (FIREBASE) ───────────────────────────────────────
    // POST /api/auth/google-login
    // Body: { "idToken": "eyJ..." } (Firebase ID Token dari client)
    // Return: { "token": "eyJ...", "refreshToken": "eyJ...", "username": "...", "namaLengkap": "..." }
    @PostMapping("/google-login")
    public ResponseEntity<?> googleLogin(@RequestBody Map<String, String> body) {
        String idToken = body.get("idToken");

        if (idToken == null || idToken.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "idToken wajib diisi"));
        }

        // Cek apakah Firebase sudah initialized
        if (!firebaseService.isInitialized()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "Firebase belum dikonfigurasi di server. " +
                            "Hubungi admin untuk mengatur FIREBASE_CONFIG_JSON environment variable."
            ));
        }

        try {
            // Verifikasi Firebase ID token
            Map<String, String> firebaseUser = firebaseService.verifyIdToken(idToken);
            
            String email = firebaseUser.get("email");
            String namaLengkap = firebaseUser.get("name");
            String picture = firebaseUser.get("picture");

            if (email == null || email.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Email tidak ditemukan di Firebase token"));
            }

            // Cek apakah user dengan email ini sudah ada di database
            var userOptional = userRepository.findAll().stream()
                    .filter(u -> email.equalsIgnoreCase(u.getEmail()))
                    .findFirst();

            User user;
            boolean isNewUser = false;

            if (userOptional.isPresent()) {
                // User sudah ada, gunakan akun lama
                user = userOptional.get();
                // Update nama lengkap dan avatar jika belum punya
                if ((user.getNamaLengkap() == null || user.getNamaLengkap().isBlank()) && namaLengkap != null) {
                    user.setNamaLengkap(namaLengkap);
                }
            } else {
                // User baru, buat akun
                isNewUser = true;
                user = new User();
                
                // Generate username dari email (ambil bagian sebelum @)
                String baseUsername = email.split("@")[0].replaceAll("[^a-zA-Z0-9_.]", "");
                String username = baseUsername;
                int counter = 1;
                
                // Cek apakah username sudah ada, jika ya tambahkan angka
                while (userRepository.existsByUsername(username)) {
                    username = baseUsername + counter;
                    counter++;
                }

                user.setUsername(username);
                user.setEmail(email);
                user.setNamaLengkap(namaLengkap != null ? namaLengkap : baseUsername);
                
                // Set password random (tidak akan pernah digunakan karena login via Google)
                user.setPassword(encoder.encode(java.util.UUID.randomUUID().toString()));
                user.setTerimaLaporanBulanan(false);
            }

            // Simpan user (baru atau update)
            userRepository.save(user);

            // Generate JWT tokens
            String accessToken = jwtUtils.generateAccessToken(user.getUsername());
            String refreshToken = jwtUtils.generateRefreshToken(user.getUsername());

            return ResponseEntity.ok(Map.of(
                    "token", accessToken,
                    "refreshToken", refreshToken,
                    "username", user.getUsername(),
                    "namaLengkap", user.getNamaLengkap(),
                    "isNewUser", isNewUser
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Firebase token tidak valid: " + e.getMessage()));
        }
    }
}
