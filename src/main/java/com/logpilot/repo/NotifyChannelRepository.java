package com.logpilot.repo;

import com.logpilot.domain.NotifyChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotifyChannelRepository extends JpaRepository<NotifyChannel, Long> {

    List<NotifyChannel> findByEnabledTrue();
}
