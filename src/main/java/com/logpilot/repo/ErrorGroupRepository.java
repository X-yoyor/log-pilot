package com.logpilot.repo;

import com.logpilot.domain.ErrorGroup;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ErrorGroupRepository extends JpaRepository<ErrorGroup, Long>, JpaSpecificationExecutor<ErrorGroup> {

    Optional<ErrorGroup> findByFingerprint(String fingerprint);

    long countByStatus(String status);

    long countByFirstSeenAfter(LocalDateTime time);

    long countByFirstSeenBetween(LocalDateTime start, LocalDateTime end);

    long countByFixedAtBetween(LocalDateTime start, LocalDateTime end);

    @Query("select distinct g.assignee from ErrorGroup g where g.assignee is not null and g.assignee <> ''")
    List<String> findDistinctAssignees();

    List<ErrorGroup> findTop20ByLastSeenAfterOrderByTotalCountDesc(LocalDateTime time);

    List<ErrorGroup> findByStatusOrderByLastSeenDesc(String status);

    Page<ErrorGroup> findByStatus(String status, Pageable pageable);

    List<ErrorGroup> findTop500ByAiAnalyzedFalseAndStatusInOrderByTotalCountDesc(List<String> statuses);
}
