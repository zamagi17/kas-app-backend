package com.zamagi.kas.controller;

import com.zamagi.kas.model.Transaksi;
import com.zamagi.kas.model.User;
import com.zamagi.kas.repository.TransaksiRepository;
import com.zamagi.kas.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import org.springframework.security.core.context.SecurityContextHolder;

@RestController
@RequestMapping("/api/transaksi")
@CrossOrigin(origins = "*") // Penting: Mengizinkan web Netlify kamu untuk mengakses API ini
public class TransaksiController {

    @Autowired
    private TransaksiRepository transaksiRepository;

    @Autowired
    private UserRepository userRepository;

    @PostMapping
    @Transactional
    public Transaksi tambahTransaksi(@RequestBody Transaksi transaksi) {
        // 1. Ambil username dari user yang sedang login via Token JWT
        String username = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();

        // 2. Cari data User lengkap dari database
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User tidak ditemukan"));

        // 3. Pasangkan User ke Transaksi sebelum di-save
        transaksi.setUser(user);

        return transaksiRepository.save(transaksi);
    }

    @GetMapping
    public List<Transaksi> getAllTransaksi() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return transaksiRepository.findByUserUsername(username);
    }

    // 3. Update Data (UPDATE)
    @PutMapping("/{id}")
    @Transactional
    public Transaksi updateTransaksi(@PathVariable Long id, @RequestBody Transaksi detailTransaksi) {
        Transaksi transaksi = transaksiRepository.findById(id).orElseThrow(() -> new RuntimeException("Data tidak ditemukan"));

        transaksi.setTanggal(detailTransaksi.getTanggal());
        transaksi.setKategori(detailTransaksi.getKategori());
        transaksi.setJenis(detailTransaksi.getJenis());
        transaksi.setSumberDana(detailTransaksi.getSumberDana());
        transaksi.setNominal(detailTransaksi.getNominal());
        transaksi.setKeterangan(detailTransaksi.getKeterangan());

        return transaksiRepository.save(transaksi);
    }

    // 4. Hapus Data (DELETE)
    @DeleteMapping("/{id}")
    @Transactional
    public String hapusTransaksi(@PathVariable Long id) {
        transaksiRepository.deleteById(id);
        return "Transaksi berhasil dihapus!";
    }
}
