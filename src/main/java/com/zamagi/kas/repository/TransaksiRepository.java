package com.zamagi.kas.repository;

import com.zamagi.kas.model.Transaksi;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TransaksiRepository extends JpaRepository<Transaksi, Long> {
    // Spring Data JPA otomatis membuatkan fungsi save(), findAll(), deleteById(), dll!
    List<Transaksi> findByUserUsernameOrderByTanggalDescIdDesc(String username);
}