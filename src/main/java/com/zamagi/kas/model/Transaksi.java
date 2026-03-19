package com.zamagi.kas.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "transaksi")
public class Transaksi {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate tanggal;
    private String kategori;
    private String jenis; 
    private String sumberDana;
    private Long nominal; // Menggunakan Long karena Rupiah jarang pakai desimal
    private String keterangan;

    // --- Constructor Kosong (Wajib untuk JPA) ---
    public Transaksi() {
    }

    // --- Getter dan Setter ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDate getTanggal() { return tanggal; }
    public void setTanggal(LocalDate tanggal) { this.tanggal = tanggal; }

    public String getKategori() { return kategori; }
    public void setKategori(String kategori) { this.kategori = kategori; }

    public String getJenis() { return jenis; }
    public void setJenis(String jenis) { this.jenis = jenis; }

    public String getSumberDana() { return sumberDana; }
    public void setSumberDana(String sumberDana) { this.sumberDana = sumberDana; }

    public Long getNominal() { return nominal; }
    public void setNominal(Long nominal) { this.nominal = nominal; }

    public String getKeterangan() { return keterangan; }
    public void setKeterangan(String keterangan) { this.keterangan = keterangan; }
    
    // --- TAMBAHAN BARU UNTUK RELASI MULTI-USER ---
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false) // Ini akan membuat kolom 'user_id' di tabel transaksi
    @JsonIgnore // Untuk menghindari error infinite loop saat generate JSON
    private User user;

    // ... (kode constructor yang sudah ada)

    // --- TAMBAHAN GETTER SETTER UNTUK USER ---
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
}