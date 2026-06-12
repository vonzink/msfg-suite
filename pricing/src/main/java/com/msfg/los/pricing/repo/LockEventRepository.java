package com.msfg.los.pricing.repo;

import com.msfg.los.pricing.domain.LockEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LockEventRepository extends JpaRepository<LockEvent, UUID> {
    List<LockEvent> findByLoanIdOrderByOccurredAtAscIdAsc(UUID loanId);
}
