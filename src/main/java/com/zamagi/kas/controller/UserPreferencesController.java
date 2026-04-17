package com.zamagi.kas.controller;

import com.zamagi.kas.model.User;
import com.zamagi.kas.repository.UserRepository;
import com.zamagi.kas.security.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/user")
@CrossOrigin(origins = "*")
public class UserPreferencesController {

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

    // ── GANTI PASSWORD ────────────────────────────────────────────────────────
    // PUT /api/user/password
    // Body: { "passwordLama": "abc123", "passwordBaru": "xyz789AB", "konfirmasi": "xyz789AB" }

    @PutMapping("/password")
    public ResponseEntity<?> gantiPassword(@RequestBody Map<String, String> body) {
        String passwordLama  = body.get("passwordLama");
        String passwordBaru  = body.get("passwordBaru");
        String konfirmasi    = body.get("konfirmasi");

        // ── Validasi input ──────────────────────────────────────────────────
        if (passwordLama == null || passwordLama.isBlank())
            return ResponseEntity.badRequest().body("Password lama wajib diisi");

        if (passwordBaru == null || passwordBaru.isBlank())
            return ResponseEntity.badRequest().body("Password baru wajib diisi");

        if (passwordBaru.length() < 8)
            return ResponseEntity.badRequest().body("Password baru minimal 8 karakter");

        if (passwordBaru.length() > 100)
            return ResponseEntity.badRequest().body("Password baru maksimal 100 karakter");

        if (!passwordBaru.matches(".*[a-zA-Z].*") || !passwordBaru.matches(".*[0-9].*"))
            return ResponseEntity.badRequest().body("Password baru harus mengandung huruf dan angka");

        if (!passwordBaru.equals(konfirmasi))
            return ResponseEntity.badRequest().body("Konfirmasi password tidak cocok");

        if (passwordLama.equals(passwordBaru))
            return ResponseEntity.badRequest().body("Password baru tidak boleh sama dengan password lama");

        // ── Verifikasi password lama ────────────────────────────────────────
        User user = getCurrentUser();
        if (!encoder.matches(passwordLama, user.getPassword())) {
            return ResponseEntity.status(401).body("Password lama salah");
        }

        // ── Simpan password baru ────────────────────────────────────────────
        user.setPassword(encoder.encode(passwordBaru));
        userRepository.save(user);

        // Kembalikan token baru agar user tidak perlu login ulang
        // (token lama masih valid sampai expire, tapi setidaknya kita beri yang baru)
        String newAccessToken  = jwtUtils.generateAccessToken(user.getUsername());
        String newRefreshToken = jwtUtils.generateRefreshToken(user.getUsername());

        return ResponseEntity.ok(Map.of(
                "message",      "Password berhasil diubah",
                "token",        newAccessToken,
                "refreshToken", newRefreshToken
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
        List<String> dompetList = body.get("dompetHarian");

        if (dompetList == null)
            return ResponseEntity.badRequest().body("Field 'dompetHarian' wajib ada");

        for (String item : dompetList) {
            if (item == null || item.isBlank())
                return ResponseEntity.badRequest().body("Nama aset tidak boleh kosong");
            if (item.length() > 100)
                return ResponseEntity.badRequest().body("Nama aset maksimal 100 karakter");
        }

        User user = getCurrentUser();
        String stored = dompetList.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("||"));

        user.setDompetHarian(stored);
        userRepository.save(user);

        return ResponseEntity.ok(Map.of(
                "message",      "Dompet harian berhasil disimpan",
                "dompetHarian", dompetList
        ));
    }

    private List<String> parseDompetHarian(String value) {
        if (value == null || value.isBlank()) return new ArrayList<>();
        return Arrays.stream(value.split("\\|\\|"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
