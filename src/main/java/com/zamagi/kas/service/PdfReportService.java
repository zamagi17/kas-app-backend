package com.zamagi.kas.service;

import com.itextpdf.barcodes.BarcodeQRCode;
import com.itextpdf.kernel.colors.Color;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.events.PdfDocumentEvent;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.xobject.PdfFormXObject;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.layout.properties.VerticalAlignment;
import com.itextpdf.io.image.ImageData;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.events.Event;
import com.itextpdf.kernel.events.IEventHandler;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState;
import com.itextpdf.layout.Canvas;
import com.itextpdf.layout.properties.HorizontalAlignment;
import com.zamagi.kas.dto.LaporanSummary;
import com.zamagi.kas.model.Transaksi;
import com.zamagi.kas.model.User;
import com.zamagi.kas.model.UtangPiutang;
import com.zamagi.kas.repository.TransaksiRepository;
import com.zamagi.kas.repository.UtangPiutangRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.itextpdf.layout.properties.VerticalAlignment;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class PdfReportService {

    @Autowired
    private TransaksiRepository transaksiRepository;

    @Autowired
    private UtangPiutangRepository utangPiutangRepository;

    private String formatRp(long nominal) {
        DecimalFormat formatter = (DecimalFormat) NumberFormat.getInstance(new Locale("id", "ID"));
        formatter.applyPattern("Rp ###,###,###,###");
        return formatter.format(nominal);
    }

    private String getNamaBulan(String tahunBulan) {
        String[] parts = tahunBulan.split("-");
        String[] nama = {"Januari", "Februari", "Maret", "April", "Mei", "Juni", "Juli", "Agustus", "September", "Oktober", "November", "Desember"};
        int month = Integer.parseInt(parts[1]);
        return nama[month - 1] + " " + parts[0];
    }

    public byte[] generateLaporanBulananPdf(User user, String filterBulan) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int fYear = Integer.parseInt(filterBulan.split("-")[0]);
        int fMonth = Integer.parseInt(filterBulan.split("-")[1]);

        // Tentukan rentang tanggal
        LocalDate startDate = LocalDate.of(fYear, fMonth, 1);
        LocalDate endDate = startDate.withDayOfMonth(startDate.lengthOfMonth());
        String username = user.getUsername();

        // 1. Tarik Saldo Awal langsung dari DB
        long sAwal = transaksiRepository.calculateSaldoAwal(username, startDate);

        // 2. Tarik Data Portofolio dari DB
        Map<String, Long> portoAllTime = new HashMap<>();
        long netWorth = 0;
        List<Object[]> portoData = transaksiRepository.getPortofolioSaldo(username, endDate);
        for (Object[] row : portoData) {
            String sumberDana = (String) row[0];
            Long saldo = (Long) row[1];
            if (sumberDana == null || sumberDana.isBlank()) {
                sumberDana = "Lain-lain";
            }

            portoAllTime.put(sumberDana, portoAllTime.getOrDefault(sumberDana, 0L) + saldo);
            netWorth += saldo;
        }

        // 3. Tarik HANYA transaksi bulan ini untuk tabel riwayat & summary bulanan
        List<Transaksi> historyBulanIni = transaksiRepository
                .findByUserUsernameAndTanggalBetweenOrderByTanggalDescIdDesc(username, startDate, endDate);

        long tMasuk = 0, tKeluar = 0, rMasuk = 0, rKeluar = 0;

        // Looping HANYA untuk data bulan ini (Sangat ringan)
        for (Transaksi row : historyBulanIni) {
            long nom = row.getNominal() != null ? row.getNominal() : 0;
            String jenis = row.getJenis() != null ? row.getJenis() : "";
            String kategori = row.getKategori() != null ? row.getKategori() : "";
            String keterangan = row.getKeterangan() != null ? row.getKeterangan() : "";

            boolean isTransferOrMutasi = "Transfer Aset (Auto)".equals(kategori)
                    && (keterangan.contains("Mutasi Masuk") || keterangan.contains("Mutasi Keluar"));

            if ("Pemasukan".equals(jenis) && !isTransferOrMutasi) {
                tMasuk += nom;
            } else if ("Pengeluaran".equals(jenis) && !isTransferOrMutasi) {
                tKeluar += nom;
            } else if ("Rencana Pemasukan".equals(jenis)) {
                rMasuk += nom;
            } else if ("Rencana Pengeluaran".equals(jenis)) {
                rKeluar += nom;
            }
        }

        long sisaKas = (sAwal + tMasuk) - tKeluar;

        // Opsional: Anda tidak perlu men-sort manual lagi karena di Repository 
        // sudah menggunakan "OrderByTanggalDescIdDesc"
        // historyBulanIni.sort(...);
        try {
            PdfWriter writer = new PdfWriter(out);
            PdfDocument pdf = new PdfDocument(writer);

//            pdf.addEventHandler(PdfDocumentEvent.END_PAGE, new WatermarkEventHandler("ZONAKAS"));
            pdf.addEventHandler(PdfDocumentEvent.END_PAGE, new PageNumberEventHandler());

            Document document = new Document(pdf);
            document.setMargins(36, 36, 50, 36);

            Color primaryColor = new DeviceRgb(30, 64, 175);
            Color accentColor = new DeviceRgb(15, 23, 42);
            Color textMuted = new DeviceRgb(100, 116, 139);
            Color lightGray = new DeviceRgb(248, 250, 252);
            Color borderColor = new DeviceRgb(226, 232, 240);

            // QR Code
            String qrUrl = "https://zona-kas.rotibuayajkt.web.id/validasi?user=" + user.getUsername() + "&bulan=" + filterBulan;
            BarcodeQRCode qrCode = new BarcodeQRCode(qrUrl);
            PdfFormXObject barcodeObject = qrCode.createFormXObject(ColorConstants.BLACK, pdf);
            Image qrImage = new Image(barcodeObject).setWidth(50).setHeight(50);
            // TAMBAHAN: Paksa gambar QR Code agar rata kanan
            qrImage.setHorizontalAlignment(HorizontalAlignment.RIGHT);

            Image logoImage = null;

            try {
                ImageData dataa = ImageDataFactory.create(
                        getClass().getResource("/static/logo.png")
                );

                logoImage = new Image(dataa)
                        .setHeight(40)
                        .setAutoScaleWidth(true);

            } catch (Exception e) {
                System.out.println("Gagal memuat logo: " + e.getMessage());
            }

            // Header
            Table headerTable = new Table(UnitValue.createPercentArray(new float[]{20, 50, 30})).useAllAvailableWidth();
            Cell logoCell = new Cell().setBorder(Border.NO_BORDER).setVerticalAlignment(VerticalAlignment.MIDDLE);
            if (logoImage != null) {
                logoCell.add(logoImage);
            } else {
                // Bisa disesuaikan dengan branding profesional Anda
                logoCell.add(new Paragraph("ASSET MANAGEMENT REPORT").setFontColor(primaryColor).setBold().setFontSize(18));
            }
            Cell centerHeader = new Cell().setBorder(Border.NO_BORDER).setVerticalAlignment(VerticalAlignment.MIDDLE);
            centerHeader.add(new Paragraph("Laporan Keuangan Pribadi").setFontColor(textMuted).setFontSize(10).setMargin(0));
            centerHeader.add(new Paragraph("Periode: " + getNamaBulan(filterBulan).toUpperCase()).setFontColor(accentColor).setBold().setFontSize(12).setMargin(0));

            // Kolom 3: QR Code
            Cell qrCell = new Cell().setBorder(Border.NO_BORDER).setTextAlignment(TextAlignment.RIGHT).setVerticalAlignment(VerticalAlignment.MIDDLE);
            qrCell.add(qrImage);
            // Tambahkan sedikit margin atas (setMarginTop) agar teks tidak menempel persis di bawah QR Code
            qrCell.add(new Paragraph("Scan untuk verifikasi").setFontSize(6).setFontColor(textMuted).setMarginTop(2));

            headerTable.addCell(logoCell);
            headerTable.addCell(centerHeader);
            headerTable.addCell(qrCell);
            document.add(headerTable);
            document.add(new Paragraph("").setBorderBottom(new SolidBorder(primaryColor, 2)).setMarginTop(10).setMarginBottom(15));

            // ==========================================
            // INFORMASI NASABAH / PENGGUNA
            // ==========================================
            Table userInfoTable = new Table(UnitValue.createPercentArray(new float[]{50, 50})).useAllAvailableWidth().setMarginBottom(15);

            // Kolom Kiri: Detail Pengguna
            Cell userCell = new Cell().setBorder(Border.NO_BORDER);
            userCell.add(new Paragraph("Akun:")
                    .setFontSize(9).setFontColor(textMuted).setMarginBottom(2));

            // Nama Lengkap (Fallback ke username jika null)
            String namaTampil = (user.getNamaLengkap() != null && !user.getNamaLengkap().isEmpty())
                    ? user.getNamaLengkap() : user.getUsername();
            userCell.add(new Paragraph(namaTampil.toUpperCase())
                    .setBold().setFontSize(11).setFontColor(accentColor).setMarginBottom(1));

            if (user.getEmail() != null && !user.getEmail().isEmpty()) {
                userCell.add(new Paragraph(user.getEmail())
                        .setFontSize(9).setFontColor(textMuted).setMarginBottom(1));
            }
            if (user.getNomorHp() != null && !user.getNomorHp().isEmpty()) {
                userCell.add(new Paragraph(user.getNomorHp())
                        .setFontSize(9).setFontColor(textMuted).setMarginBottom(1));
            }

            // Kolom Kanan: Detail Dokumen Tambahan
            Cell reportInfoCell = new Cell().setBorder(Border.NO_BORDER).setTextAlignment(TextAlignment.RIGHT);
            reportInfoCell.add(new Paragraph("Tipe Dokumen:")
                    .setFontSize(9).setFontColor(textMuted).setMarginBottom(2));
            reportInfoCell.add(new Paragraph("Ringkasan Kas & Portofolio")
                    .setBold().setFontSize(10).setFontColor(accentColor).setMarginBottom(1));
            reportInfoCell.add(new Paragraph("Mata Uang: IDR (Rupiah)")
                    .setFontSize(9).setFontColor(textMuted).setMarginBottom(1));

            userInfoTable.addCell(userCell);
            userInfoTable.addCell(reportInfoCell);
            document.add(userInfoTable);

            // ==========================================
            // 1. SUMMARY CARDS (RINGKASAN PEMASUKAN & PENGELUARAN)
            // ==========================================
            document.add(new Paragraph("RINGKASAN PEMASUKAN & PENGELUARAN")
                    .setFontColor(accentColor).setBold().setFontSize(12).setMarginBottom(2));

            document.add(new Paragraph("Ikhtisar pergerakan uang selama periode berjalan. Evaluasi rasio pemasukan dan pengeluaran ini berguna untuk mengukur efisiensi keuangan Anda.")
                    .setFontColor(textMuted).setFontSize(9).setItalic().setMarginBottom(10));

            Table tableRingkasan = new Table(UnitValue.createPercentArray(4)).useAllAvailableWidth();
            tableRingkasan.addCell(createModernSummaryCell("Saldo Awal", formatRp(sAwal), textMuted, accentColor));

            tableRingkasan.addCell(createModernSummaryCell("Total Pemasukan", formatRp(tMasuk), textMuted, new DeviceRgb(5, 150, 105)));

            tableRingkasan.addCell(createModernSummaryCell("Total Pengeluaran", formatRp(tKeluar), textMuted, new DeviceRgb(220, 38, 38)));

            tableRingkasan.addCell(createModernSummaryCell("Sisa Saldo", formatRp(sisaKas), textMuted, primaryColor));

            document.add(tableRingkasan);
            document.add(new Paragraph("\n"));

            // ==========================================
            // 2. POSISI ASET & NET WORTH
            // ==========================================
            document.add(new Paragraph("SALDO PER DOMPET / REKENING")
                    .setFontColor(accentColor).setBold().setFontSize(12).setMarginBottom(2));

            // Menghapus istilah "net worth" dan "instrumen investasi" yang terlalu kaku
            document.add(new Paragraph("Distribusi saldo uang Anda saat ini yang tersebar di berbagai dompet dan rekening bank.")
                    .setFontColor(textMuted).setFontSize(9).setItalic().setMarginBottom(10));

            Table tableAset = new Table(UnitValue.createPercentArray(new float[]{60, 40})).useAllAvailableWidth();
            for (Map.Entry<String, Long> entry : portoAllTime.entrySet()) {
                if (entry.getValue() != 0) {
                    tableAset.addCell(new Cell()
                            .add(new Paragraph(entry.getKey()).setFontSize(10).setFontColor(accentColor))
                            .setBorder(Border.NO_BORDER)
                            .setBorderBottom(new SolidBorder(borderColor, 1))
                            .setPadding(6));
                    tableAset.addCell(new Cell()
                            .add(new Paragraph(formatRp(entry.getValue())).setFontSize(10).setBold().setTextAlignment(TextAlignment.RIGHT).setFontColor(accentColor))
                            .setBorder(Border.NO_BORDER)
                            .setBorderBottom(new SolidBorder(borderColor, 1))
                            .setPadding(6));
                }
            }

            // Perbaikan visual baris Total Aset agar lebih modern (diberi background tipis)
            Cell cellTotalLabel = new Cell()
                    .add(new Paragraph("Total Saldo Dompet").setFontColor(primaryColor).setBold().setFontSize(11))
                    .setBorder(Border.NO_BORDER)
                    .setBackgroundColor(lightGray)
                    .setPadding(8)
                    .setMarginTop(4);

            Cell cellTotalValue = new Cell()
                    .add(new Paragraph(formatRp(netWorth)).setFontColor(primaryColor).setBold().setFontSize(12).setTextAlignment(TextAlignment.RIGHT))
                    .setBorder(Border.NO_BORDER)
                    .setBackgroundColor(lightGray)
                    .setPadding(8)
                    .setMarginTop(4);

            tableAset.addCell(cellTotalLabel);
            tableAset.addCell(cellTotalValue);
            document.add(tableAset);
            document.add(new Paragraph("\n"));

            // Transactions Table
            document.add(new Paragraph("RIWAYAT TRANSAKSI").setFontColor(accentColor).setBold().setFontSize(11).setMarginBottom(5));
            Table tableTrans = new Table(UnitValue.createPercentArray(new float[]{12, 18, 30, 20, 20})).useAllAvailableWidth();
            String[] headers = {"Tanggal", "Kategori", "Keterangan", "Dompet / Rekening", "Nominal"};
            for (String header : headers) {
                tableTrans.addHeaderCell(new Cell().add(new Paragraph(header).setFontColor(ColorConstants.WHITE).setBold().setFontSize(9)).setBackgroundColor(primaryColor).setBorder(Border.NO_BORDER).setPadding(6));
            }

            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/yy");
            boolean isLatarBelang = false;
            for (Transaksi t : historyBulanIni) {
                Color rowBg = isLatarBelang ? lightGray : ColorConstants.WHITE;
                tableTrans.addCell(createDataCell(t.getTanggal().format(dtf), rowBg, borderColor));
                tableTrans.addCell(createDataCell(t.getKategori(), rowBg, borderColor));
                tableTrans.addCell(createDataCell(t.getKeterangan(), rowBg, borderColor));
                tableTrans.addCell(createDataCell(t.getSumberDana(), rowBg, borderColor));

                Paragraph pNominal = new Paragraph(formatRp(t.getNominal())).setFontSize(9).setTextAlignment(TextAlignment.RIGHT);
                if ("Pengeluaran".equals(t.getJenis())) {
                    pNominal.setFontColor(new DeviceRgb(220, 38, 38)).setBold();
                } else if ("Pemasukan".equals(t.getJenis())) {
                    pNominal.setFontColor(new DeviceRgb(5, 150, 105)).setBold();
                }

                tableTrans.addCell(new Cell().add(pNominal).setBackgroundColor(rowBg).setBorder(Border.NO_BORDER).setBorderBottom(new SolidBorder(borderColor, 0.5f)).setPadding(5));
                isLatarBelang = !isLatarBelang;
            }
            document.add(tableTrans);

            // Footer
            String printDate = LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMMM yyyy", new Locale("id", "ID")));
            document.add(new Paragraph("Digenerate otomatis oleh sistem Zonakas pada " + printDate).setFontSize(8).setFontColor(textMuted).setTextAlignment(TextAlignment.CENTER).setMarginTop(20));

            document.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return out.toByteArray();
    }

    private Cell createModernSummaryCell(String label, String value, Color labelColor, Color valueColor) {
        return new Cell().setPadding(10).setBorder(Border.NO_BORDER).setBackgroundColor(new DeviceRgb(248, 250, 252))
                .add(new Paragraph(label).setFontSize(9).setFontColor(labelColor).setMarginBottom(2))
                .add(new Paragraph(value).setBold().setFontSize(12).setFontColor(valueColor));
    }

    private Cell createDataCell(String text, Color bgColor, Color borderColor) {
        return new Cell().add(new Paragraph(text != null ? text : "").setFontSize(9))
                .setBackgroundColor(bgColor).setBorder(Border.NO_BORDER).setBorderBottom(new SolidBorder(borderColor, 0.5f)).setPadding(5);
    }

    public LaporanSummary getSummary(User user, String filterBulan) {
        int fYear = Integer.parseInt(filterBulan.split("-")[0]);
        int fMonth = Integer.parseInt(filterBulan.split("-")[1]);
        long tMasuk = 0, tKeluar = 0;
        List<Transaksi> data = transaksiRepository.findByUserUsernameOrderByTanggalDescIdDesc(user.getUsername());
        for (Transaksi row : data) {
            if (row.getTanggal().getYear() == fYear && row.getTanggal().getMonthValue() == fMonth) {
                long nom = row.getNominal() != null ? row.getNominal() : 0;
                if ("Pemasukan".equals(row.getJenis())) {
                    tMasuk += nom;
                } else if ("Pengeluaran".equals(row.getJenis())) {
                    tKeluar += nom;
                }
            }
        }
        return new LaporanSummary(tMasuk, tKeluar);
    }

    private static class PageNumberEventHandler implements IEventHandler {

        @Override
        public void handleEvent(Event event) {
            PdfDocumentEvent docEvent = (PdfDocumentEvent) event;
            PdfDocument pdfDoc = docEvent.getDocument();
            PdfPage page = docEvent.getPage();
            int pageNumber = pdfDoc.getPageNumber(page);
            Rectangle pageSize = page.getPageSize();

            PdfCanvas pdfCanvas = new PdfCanvas(page.newContentStreamBefore(), page.getResources(), pdfDoc);
            Canvas canvas = new Canvas(pdfCanvas, pageSize);

            DeviceRgb textMuted = new DeviceRgb(100, 116, 139);

            Paragraph p = new Paragraph("Halaman " + pageNumber)
                    .setFontSize(8)
                    .setFontColor(textMuted);

            // Posisi rata kanan bawah
            canvas.showTextAligned(p, pageSize.getWidth() - 36, 20, TextAlignment.RIGHT);
            canvas.close();
        }
    }

    private static class WatermarkEventHandler implements IEventHandler {

        private final String watermarkText;

        // Constructor untuk menerima teks watermark (misal: "ZONAKAS")
        public WatermarkEventHandler(String watermarkText) {
            this.watermarkText = watermarkText;
        }

        @Override
        public void handleEvent(Event event) {
            PdfDocumentEvent docEvent = (PdfDocumentEvent) event;
            PdfDocument pdfDoc = docEvent.getDocument();
            PdfPage page = docEvent.getPage();
            Rectangle pageSize = page.getPageSize();

            // PENTING: Gunakan newContentStreamBefore() agar watermark 
            // digambar LEBIH DULU sehingga posisinya ada di BELAKANG teks laporan.
            PdfCanvas pdfCanvas = new PdfCanvas(page.newContentStreamBefore(), page.getResources(), pdfDoc);

            // Mengatur transparansi (opacity) agar tidak mengganggu keterbacaan data
            // Nilai 0.15f berarti 15% terlihat (sangat tipis dan elegan)
            PdfExtGState transparency = new PdfExtGState().setFillOpacity(0.15f);
            pdfCanvas.setExtGState(transparency);

            Canvas canvas = new Canvas(pdfCanvas, pageSize);

            // --- PERUBAHAN ADA DI BAGIAN INI ---
            // Terapkan ukuran, warna, dan ketebalan font langsung ke Canvas
            canvas.setFontSize(80)
                    .setFontColor(ColorConstants.GRAY)
                    .setBold();

            // Mencari titik tengah halaman untuk sumbu X dan Y
            float x = pageSize.getWidth() / 2;
            float y = pageSize.getHeight() / 2;

            // Masukkan variabel String 'watermarkText' langsung sebagai parameter pertama
            canvas.showTextAligned(watermarkText, x, y, TextAlignment.CENTER, VerticalAlignment.MIDDLE, 0.785f);
            // -----------------------------------

            canvas.close();
        }
    }
}
