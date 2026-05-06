package com.zamagi.kas.repository;

import com.zamagi.kas.model.ScheduledTransaction;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduledTransactionRepository extends JpaRepository<ScheduledTransaction, Long> {

    List<ScheduledTransaction> findByUserUsernameOrderByNextRunAtAscIdDesc(String username);

    List<ScheduledTransaction> findTop100ByActiveTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(OffsetDateTime now);
}
