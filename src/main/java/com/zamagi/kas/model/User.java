package com.zamagi.kas.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "user_seq")
    @SequenceGenerator(name = "user_seq", sequenceName = "users_id_seq", allocationSize = 1)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    @Column(name = "auth_provider")
    private String authProvider = "LOCAL";

    // Menyimpan daftar aset dompet harian sebagai JSON string
    // Contoh: ["BCA","SeaBank","Dompet Tunai"]
    @Column(name = "dompet_harian", columnDefinition = "TEXT")
    private String dompetHarian;

    @Column(name = "nama_lengkap")
    private String namaLengkap;

    @Column(name = "email")
    private String email;

    @Column(name = "nomor_hp")
    private String nomorHp;

    @Column(name = "avatar_path")
    private String avatarPath;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Column(name = "terima_laporan_bulanan", columnDefinition = "BOOLEAN DEFAULT FALSE")
    private Boolean terimaLaporanBulanan = false;

    @Column(name = "password_reset_token")
    private String passwordResetToken;

    @Column(name = "password_reset_expires_at")
    private LocalDateTime passwordResetExpiresAt;

    @Column(name = "last_forgot_password_request")
    private LocalDateTime lastForgotPasswordRequest;

    @Column(name = "forgot_password_request_count", columnDefinition = "integer default 0")
    private Integer forgotPasswordRequestCount = 0;

    @Column(name = "token_version", columnDefinition = "integer default 0")
    private Integer tokenVersion = 0;

    // --- Constructor Kosong ---
    public User() {
    }

    // --- Getter dan Setter ---
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getAuthProvider() {
        return authProvider == null ? "LOCAL" : authProvider;
    }

    public void setAuthProvider(String authProvider) {
        this.authProvider = authProvider;
    }

    public String getDompetHarian() {
        return dompetHarian;
    }

    public void setDompetHarian(String dompetHarian) {
        this.dompetHarian = dompetHarian;
    }

    public String getNamaLengkap() {
        return namaLengkap;
    }

    public void setNamaLengkap(String namaLengkap) {
        this.namaLengkap = namaLengkap;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getNomorHp() {
        return nomorHp;
    }

    public void setNomorHp(String nomorHp) {
        this.nomorHp = nomorHp;
    }

    public String getAvatarPath() {
        return avatarPath;
    }

    public void setAvatarPath(String avatarPath) {
        this.avatarPath = avatarPath;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Boolean getTerimaLaporanBulanan() {
        return terimaLaporanBulanan;
    }

    public void setTerimaLaporanBulanan(Boolean terimaLaporanBulanan) {
        this.terimaLaporanBulanan = terimaLaporanBulanan;
    }

    public String getPasswordResetToken() {
        return passwordResetToken;
    }

    public void setPasswordResetToken(String passwordResetToken) {
        this.passwordResetToken = passwordResetToken;
    }

    public LocalDateTime getPasswordResetExpiresAt() {
        return passwordResetExpiresAt;
    }

    public void setPasswordResetExpiresAt(LocalDateTime passwordResetExpiresAt) {
        this.passwordResetExpiresAt = passwordResetExpiresAt;
    }

    public LocalDateTime getLastForgotPasswordRequest() {
        return lastForgotPasswordRequest;
    }

    public void setLastForgotPasswordRequest(LocalDateTime lastForgotPasswordRequest) {
        this.lastForgotPasswordRequest = lastForgotPasswordRequest;
    }

    public Integer getForgotPasswordRequestCount() {
        return forgotPasswordRequestCount != null ? forgotPasswordRequestCount : 0;
    }

    public void setForgotPasswordRequestCount(Integer forgotPasswordRequestCount) {
        this.forgotPasswordRequestCount = forgotPasswordRequestCount;
    }

    public Integer getTokenVersion() {
        return tokenVersion != null ? tokenVersion : 0;
    }

    public void setTokenVersion(Integer tokenVersion) {
        this.tokenVersion = tokenVersion;
    }

    public void incrementTokenVersion() {
        this.tokenVersion = getTokenVersion() + 1;
    }
}
