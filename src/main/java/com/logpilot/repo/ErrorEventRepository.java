package com.logpilot.repo;

import com.logpilot.domain.ErrorEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ErrorEventRepository extends JpaRepository<ErrorEvent, Long> {

    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    long countByCreatedAtAfterAndSourceId(LocalDateTime time, Long sourceId);

    long countByCreatedAtAfterAndSourceIdNotIn(LocalDateTime time, java.util.Collection<Long> sourceIds);

    long countByCreatedAtAfter(LocalDateTime time);

    long countByGroupId(Long groupId);

    long countByGroupIdAndCreatedAtAfter(Long groupId, LocalDateTime time);

    long deleteByCreatedAtBefore(LocalDateTime time);

    List<ErrorEvent> findTop200ByGroupIdOrderByCreatedAtDesc(Long groupId);

    ErrorEvent findFirstByGroupIdOrderByCreatedAtDesc(Long groupId);

    Page<ErrorEvent> findByGroupId(Long groupId, Pageable pageable);

    Page<ErrorEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("select e.filePath, count(e) from ErrorEvent e where e.createdAt >= :t group by e.filePath order by count(e) desc")
    List<Object[]> countByFileSince(@Param("t") LocalDateTime t);

    @Query("select e.groupId, count(e) from ErrorEvent e where e.createdAt >= :t group by e.groupId order by count(e) desc")
    List<Object[]> countByGroupSince(@Param("t") LocalDateTime t);

    @Query("select e.groupId, count(e) from ErrorEvent e where e.createdAt >= :f and e.createdAt < :t group by e.groupId order by count(e) desc")
    List<Object[]> countByGroupBetween(@Param("f") LocalDateTime from, @Param("t") LocalDateTime to);

    @Query("select e.sourceId, count(e) from ErrorEvent e where e.createdAt >= :t group by e.sourceId order by count(e) desc")
    List<Object[]> countBySourceSince(@Param("t") LocalDateTime t);

    @Query("select e.sourceId, count(e) from ErrorEvent e where e.createdAt >= :f and e.createdAt < :t group by e.sourceId order by count(e) desc")
    List<Object[]> countBySourceBetween(@Param("f") LocalDateTime from, @Param("t") LocalDateTime to);
}
