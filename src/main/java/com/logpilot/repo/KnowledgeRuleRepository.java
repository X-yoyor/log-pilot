package com.logpilot.repo;

import com.logpilot.domain.KnowledgeRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeRuleRepository extends JpaRepository<KnowledgeRule, Long> {

    List<KnowledgeRule> findByEnabledTrueOrderBySortOrderAscIdAsc();
}
