package com.logpilot.repo;

import com.logpilot.domain.SourceFileOffset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SourceFileOffsetRepository extends JpaRepository<SourceFileOffset, Long> {

    Optional<SourceFileOffset> findBySourceIdAndFilePath(Long sourceId, String filePath);

    List<SourceFileOffset> findBySourceId(Long sourceId);
}
