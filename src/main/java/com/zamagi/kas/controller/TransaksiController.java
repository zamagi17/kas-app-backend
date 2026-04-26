package com.zamagi.kas.controller;

import com.zamagi.kas.dto.TransferRequest;
import com.zamagi.kas.model.Transaksi;
import com.zamagi.kas.model.User;
import com.zamagi.kas.model.UtangPiutang;
import com.zamagi.kas.repository.TransaksiRepository;
import com.zamagi.kas.repository.UserRepository;
import com.zamagi.kas.repository.UtangPiutangRepository;
import com.zamagi.kas.service.PdfReportService;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

@RestController
@RequestMapping("/api/transaksi")
@CrossOrigin(origins = "*")
public class TransaksiController {

    @Autowired
    private TransaksiRepository transaksiRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UtangPiutangRepository utangPiutangRepository;

    @Autowired
    private PdfReportService pdfReportService;

    // Daftar jenis yang diizinkan
    private static final List<String> JENIS_VALID = List.of(
            "Pemasukan", "Pengeluaran",
            "Rencana Pemasukan", "Rencana Pengeluaran",
            "Utang Masuk", "Piutang Keluar",
            "Bayar Utang", "Terima Piutang"
    );

    // Helper validasi, return pesan error atau null kalau valid
    private String validasiTransaksi(Transaksi t) {
        if (t.getNominal() == null || t.getNominal() <= 0) {
            return "Nominal harus lebih dari 0";
        }

        if (t.getNominal() > 999_999_999_999L) {
            return "Nominal maksimal Rp 999.999.999.999";
        }

        if (t.getJenis() == null || !JENIS_VALID.contains(t.getJenis())) {
            return "Jenis transaksi tidak valid";
        }

        if (t.getTanggal() == null) {
            return "Tanggal wajib diisi";
        }

        if (t.getTanggal().isBefore(LocalDate.of(2000, 1, 1))) {
            return "Tanggal terlalu jauh ke belakang (minimal tahun 2000)";
        }

        if (t.getTanggal().isAfter(LocalDate.now().plusYears(1))) {
            return "Tanggal terlalu jauh ke depan (maksimal 1 tahun)";
        }

        if (t.getKategori() == null || t.getKategori().isBlank()) {
            return "Kategori wajib diisi";
        }

        if (t.getKategori().length() > 100) {
            return "Kategori maksimal 100 karakter";
        }

        if (t.getSumberDana() == null || t.getSumberDana().isBlank()) {
            return "Sumber dana wajib diisi";
        }

        if (t.getSumberDana().length() > 100) {
            return "Sumber dana maksimal 100 karakter";
        }

        if (t.getKeterangan() != null && t.getKeterangan().length() > 500) {
            return "Keterangan maksimal 500 karakter";
        }

        return null; // valid
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> tambahTransaksi(@RequestBody Transaksi transaksi) {
        String error = validasiTransaksi(transaksi);
        if (error != null) {
            return ResponseEntity.badRequest().body(error);
        }

        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User tidak ditemukan"));
        transaksi.setUser(user);

        return ResponseEntity.ok(transaksiRepository.save(transaksi));
    }

    @GetMapping
    public ResponseEntity<?> getAllTransaksi(@RequestParam(required = false) String bulan) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();

        if (bulan == null || bulan.isBlank()) {
            // Fallback jika tidak ada parameter bulan dikirim, ambil bulan ini
            bulan = YearMonth.now().toString();
        }

        try {
            YearMonth yearMonth = YearMonth.parse(bulan);
            LocalDate startDate = yearMonth.atDay(1);
            LocalDate endDate = yearMonth.atEndOfMonth();

            List<Transaksi> transaksiBulanIni = transaksiRepository
                    .findByUserUsernameAndTanggalBetweenOrderByTanggalDescIdDesc(username, startDate, endDate);

            long totalTransaksiKeseluruhan = transaksiRepository.countByUserUsername(username);

            boolean isNewUser = totalTransaksiKeseluruhan <= 10;

            Map<String, Object> response = new HashMap<>();
            response.put("data", transaksiBulanIni);
            response.put("showGuide", isNewUser);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Format bulan tidak valid. Gunakan format YYYY-MM");
        }
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> updateTransaksi(@PathVariable Long id, @RequestBody Transaksi detailTransaksi) {
        String error = validasiTransaksi(detailTransaksi);
        if (error != null) {
            return ResponseEntity.badRequest().body(error);
        }

        Transaksi transaksi = transaksiRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Data tidak ditemukan"));

        transaksi.setTanggal(detailTransaksi.getTanggal());
        transaksi.setKategori(detailTransaksi.getKategori());
        transaksi.setJenis(detailTransaksi.getJenis());
        transaksi.setSumberDana(detailTransaksi.getSumberDana());
        transaksi.setNominal(detailTransaksi.getNominal());
        transaksi.setKeterangan(detailTransaksi.getKeterangan());

        return ResponseEntity.ok(transaksiRepository.save(transaksi));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> hapusTransaksi(@PathVariable Long id) {
        Transaksi transaksi = transaksiRepository.findById(id)
                .orElse(null);
        if (transaksi == null) {
            return ResponseEntity.badRequest().body("Data tidak ditemukan");
        }

        // Jika kategori "Bayar Utang" atau "Terima Piutang", kurangi sudah_bayar di utang_piutang
        if ("Bayar Utang".equals(transaksi.getKategori()) || "Terima Piutang".equals(transaksi.getKategori())) {
            // Ekstrak ID dari keterangan, misalnya "[ID:123]"
            String keterangan = transaksi.getKeterangan();
            if (keterangan != null) {
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\[ID:(\\d+)\\]");
                java.util.regex.Matcher matcher = pattern.matcher(keterangan);
                if (matcher.find()) {
                    Long utangId = Long.parseLong(matcher.group(1));
                    UtangPiutang utang = utangPiutangRepository.findById(utangId).orElse(null);
                    if (utang != null) {
                        // Kurangi sudah_bayar
                        Long newSudahBayar = utang.getSudahDibayar() - transaksi.getNominal();
                        if (newSudahBayar < 0) {
                            newSudahBayar = 0L; // Tidak boleh negatif
                        }
                        utang.setSudahDibayar(newSudahBayar);

                        // Update status
                        if (utang.getSisaTagihan() <= 0) {
                            utang.setStatus("Lunas");
                        } else {
                            utang.setStatus("Belum Lunas");
                        }

                        utangPiutangRepository.save(utang);
                    }
                }
            }
        }

        transaksiRepository.deleteById(id);
        return ResponseEntity.ok("Transaksi berhasil dihapus!");
    }

    @GetMapping("/download-laporan")
    public ResponseEntity<byte[]> downloadLaporan(@RequestParam String bulan) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User tidak ditemukan"));

        byte[] pdfBytes = pdfReportService.generateLaporanBulananPdf(user, bulan);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "Laporan_" + username + "_" + bulan + ".pdf");

        return ResponseEntity.ok()
                .headers(headers)
                .body(pdfBytes);
    }

    @GetMapping("/ringkasan")
    public ResponseEntity<?> getRingkasanLaporan(@RequestParam String bulan) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();

        YearMonth yearMonth = YearMonth.parse(bulan);
        LocalDate startDate = yearMonth.atDay(1);
        LocalDate endDate = yearMonth.atEndOfMonth();
        LocalDate today = LocalDate.now();

        // 1. Tarik Saldo Awal langsung dari DB menggunakan JPQL
        Long sAwalDb = transaksiRepository.calculateSaldoAwal(username, startDate);
        long sAwal = sAwalDb != null ? sAwalDb : 0L;

        // 2. Tarik Data Portofolio & Net Worth langsung dari DB menggunakan JPQL
        Map<String, Long> portofolio = new HashMap<>();
        long netWorth = 0;
        List<Object[]> portoData = transaksiRepository.getPortofolioSaldo(username, endDate);
        for (Object[] row : portoData) {
            String sumberDana = (String) row[0];
            Long saldo = (Long) row[1];
            if (sumberDana == null || sumberDana.isBlank()) {
                sumberDana = "Lain-lain";
            }

            portofolio.put(sumberDana, portofolio.getOrDefault(sumberDana, 0L) + saldo);
            netWorth += saldo;
        }

        // 3. Tarik HANYA transaksi bulan ini untuk di-looping
        List<Transaksi> historyBulanIni = transaksiRepository
                .findByUserUsernameAndTanggalBetweenOrderByTanggalDescIdDesc(username, startDate, endDate);

        long tMasuk = 0, tKeluar = 0, rMasuk = 0, rKeluar = 0;
        long hMasuk = 0, hKeluar = 0;
        Map<String, Long> chartPengeluaran = new HashMap<>();

        // Looping ini sekarang sangat ringan karena hanya memproses data 1 bulan
        for (Transaksi row : historyBulanIni) {
            LocalDate rowDate = row.getTanggal();
            long nom = row.getNominal() != null ? row.getNominal() : 0;
            String jenis = row.getJenis() != null ? row.getJenis() : "";
            String kategori = row.getKategori() != null ? row.getKategori() : "";
            String keterangan = row.getKeterangan() != null ? row.getKeterangan() : "";

            boolean isTransferOrMutasi = "Transfer Aset (Auto)".equals(kategori)
                    && (keterangan.contains("Mutasi Masuk") || keterangan.contains("Mutasi Keluar"));

            // Hitung Transaksi Hari Ini (Akan otomatis terhitung jika user melihat bulan berjalan)
            if (rowDate.isEqual(today) && !isTransferOrMutasi) {
                if ("Pemasukan".equals(jenis)) {
                    hMasuk += nom;
                } else if ("Pengeluaran".equals(jenis)) {
                    hKeluar += nom;
                }
            }

            if ("Pemasukan".equals(jenis) && !isTransferOrMutasi) {
                tMasuk += nom;
            } else if ("Pengeluaran".equals(jenis)) {
                if (!isTransferOrMutasi) {
                    tKeluar += nom;
                    chartPengeluaran.put(kategori, chartPengeluaran.getOrDefault(kategori, 0L) + nom);
                }
            } else if ("Rencana Pemasukan".equals(jenis)) {
                rMasuk += nom;
            } else if ("Rencana Pengeluaran".equals(jenis)) {
                rKeluar += nom;
            }
        }

        // Susun Response
        Map<String, Object> response = new HashMap<>();
        Map<String, Long> summary = new HashMap<>();
        summary.put("saldoAwal", sAwal);
        summary.put("totalMasuk", tMasuk);
        summary.put("totalKeluar", tKeluar);
        summary.put("rencanaMasuk", rMasuk);
        summary.put("rencanaKeluar", rKeluar);
        summary.put("totalNetWorth", netWorth);
        summary.put("hariIniMasuk", hMasuk);
        summary.put("hariIniKeluar", hKeluar);

        response.put("summary", summary);
        response.put("portofolio", portofolio);
        response.put("chartPengeluaran", chartPengeluaran);
        response.put("historyData", historyBulanIni);

        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/transfer")
    @Transactional
    public ResponseEntity<?> transferAset(@RequestBody TransferRequest req) {
        
        // 1. Validasi Dasar
        if (req.getNominal() == null || req.getNominal() <= 0) {
            return ResponseEntity.badRequest().body("Nominal transfer harus lebih dari 0");
        }
        if (req.getSumberDana() == null || req.getSumberDanaTujuan() == null || 
            req.getSumberDana().equals(req.getSumberDanaTujuan())) {
            return ResponseEntity.badRequest().body("Dompet asal dan tujuan tidak valid atau sama");
        }

        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User tidak ditemukan"));

        String baseKeterangan = (req.getKeterangan() != null && !req.getKeterangan().isBlank()) 
                                ? req.getKeterangan() : "Transfer Aset";

        // 2. Buat Transaksi Mutasi Keluar (Dari Dompet Asal)
        Transaksi tKeluar = new Transaksi();
        tKeluar.setUser(user);
        tKeluar.setTanggal(req.getTanggal() != null ? req.getTanggal() : LocalDate.now());
        tKeluar.setJenis("Pengeluaran");
        tKeluar.setKategori("Transfer Aset (Auto)");
        tKeluar.setSumberDana(req.getSumberDana());
        tKeluar.setNominal(req.getNominal());
        tKeluar.setKeterangan(baseKeterangan + " (Mutasi Keluar)");
        transaksiRepository.save(tKeluar);

        // 3. Buat Transaksi Mutasi Masuk (Ke Dompet Tujuan)
        Transaksi tMasuk = new Transaksi();
        tMasuk.setUser(user);
        tMasuk.setTanggal(tKeluar.getTanggal());
        tMasuk.setJenis("Pemasukan");
        tMasuk.setKategori("Transfer Aset (Auto)");
        tMasuk.setSumberDana(req.getSumberDanaTujuan());
        tMasuk.setNominal(req.getNominal());
        tMasuk.setKeterangan(baseKeterangan + " (Mutasi Masuk)");
        transaksiRepository.save(tMasuk);

        return ResponseEntity.ok("Transfer berhasil dicatat");
    }
}
