package com.logpilot.repo;

import com.logpilot.domain.ServerNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ServerNodeRepository extends JpaRepository<ServerNode, Long> {

    List<ServerNode> findByEnabledTrue();

    List<ServerNode> findByTypeOrderByCreatedAtDesc(String type);
}
