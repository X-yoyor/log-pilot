package com.logpilot.repo;

import com.logpilot.domain.Silence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface SilenceRepository extends JpaRepository<Silence, Long> {

    List<Silence> findByUntilAtAfter(LocalDateTime now);

    List<Silence> findBySourceId(Long sourceId);

    List<Silence> findByGroupIdAndUntilAtAfter(Long groupId, LocalDateTime now);
}
