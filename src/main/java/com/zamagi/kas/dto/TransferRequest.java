package com.zamagi.kas.dto;

import java.time.LocalDate;

public class TransferRequest {

    private LocalDate tanggal;
    private String sumberDana;
    private String sumberDanaTujuan;
    private Long nominal;
    private String keterangan;

    // Getters
    public LocalDate getTanggal() {
        return tanggal;
    }

    public String getSumberDana() {
        return sumberDana;
    }

    public String getSumberDanaTujuan() {
        return sumberDanaTujuan;
    }

    public Long getNominal() {
        return nominal;
    }

    public String getKeterangan() {
        return keterangan;
    }
}
