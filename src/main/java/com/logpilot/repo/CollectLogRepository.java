package com.logpilot.repo;

import com.logpilot.domain.CollectLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface CollectLogRepository extends JpaRepository<CollectLog, Long> {

    Page<CollectLog> findAllByOrderByStartAtDesc(Pageable pageable);

    List<CollectLog> findTop400ByOrderByStartAtDesc();

    long countByStatusAndStartAtBetween(String status, LocalDateTime start, LocalDateTime end);

    long deleteByStartAtBefore(LocalDateTime time);
}
