package com.zamagi.kas.controller;

import com.zamagi.kas.model.User;
import com.zamagi.kas.repository.UserRepository;
import com.zamagi.kas.security.JwtUtils;
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

    private String validasiUser(String username, String password) {
        if (username == null || username.isBlank())
            return "Username tidak boleh kosong";

        if (username.length() < 3)
            return "Username minimal 3 karakter";

        if (username.length() > 50)
            return "Username maksimal 50 karakter";

        // Hanya boleh huruf, angka, underscore, dan titik
        if (!username.matches("^[a-zA-Z0-9_.]+$"))
            return "Username hanya boleh huruf, angka, titik, dan underscore";

        if (password == null || password.isBlank())
            return "Password tidak boleh kosong";

        if (password.length() < 6)
            return "Password minimal 6 karakter";

        if (password.length() > 100)
            return "Password maksimal 100 karakter";

        return null; // valid
    }

    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody User user) {
        String error = validasiUser(user.getUsername(), user.getPassword());
        if (error != null) return ResponseEntity.badRequest().body(error);

        if (userRepository.existsByUsername(user.getUsername()))
            return ResponseEntity.badRequest().body("Username sudah terdaftar!");

        user.setPassword(encoder.encode(user.getPassword()));
        userRepository.save(user);
        return ResponseEntity.ok("User berhasil didaftarkan!");
    }

    @PostMapping("/login")
    public ResponseEntity<?> authenticateUser(@RequestBody User user) {
        // Validasi input login juga
        if (user.getUsername() == null || user.getUsername().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Username wajib diisi"));

        if (user.getPassword() == null || user.getPassword().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Password wajib diisi"));

        var userOptional = userRepository.findByUsername(user.getUsername());
        if (userOptional.isPresent()) {
            User u = userOptional.get();
            if (encoder.matches(user.getPassword(), u.getPassword())) {
                String token = jwtUtils.generateJwtToken(u.getUsername());
                return ResponseEntity.ok(Map.of(
                    "token", token,
                    "username", u.getUsername()
                ));
            }
        }

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Username atau Password salah!"));
    }
}