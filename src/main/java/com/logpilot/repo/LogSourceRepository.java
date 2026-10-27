package com.logpilot.repo;

import com.logpilot.domain.LogSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LogSourceRepository extends JpaRepository<LogSource, Long> {

    List<LogSource> findByEnabledTrue();

    List<LogSource> findByServerId(Long serverId);

    List<LogSource> findByEnabledTrueAndNextCollectTimeLessThanEqual(java.time.LocalDateTime time);
}
