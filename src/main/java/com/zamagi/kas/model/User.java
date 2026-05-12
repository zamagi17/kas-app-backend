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
}
