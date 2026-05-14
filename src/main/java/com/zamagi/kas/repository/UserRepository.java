package com.zamagi.kas.repository;

import com.zamagi.kas.model.User;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.Query;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // Fungsi ajaib: Spring Data JPA akan otomatis membuatkan query SQL-nya!
    // "SELECT * FROM users WHERE username = ?"
    Optional<User> findByUsername(String username);

    // Fungsi untuk mengecek apakah username sudah dipakai saat mendaftar (Register)
    Boolean existsByUsername(String username);

    // Method untuk mencari user berdasarkan password reset token
    Optional<User> findByPasswordResetToken(String passwordResetToken);

    @Query("SELECT u FROM User u WHERE u.terimaLaporanBulanan = true AND u.email IS NOT NULL AND u.email <> ''")
    List<User> findUsersForMonthlyReport();
}
