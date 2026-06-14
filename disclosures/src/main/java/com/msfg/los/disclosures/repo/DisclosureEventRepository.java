package com.msfg.los.disclosures.repo;

import com.msfg.los.disclosures.domain.DisclosureEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DisclosureEventRepository extends JpaRepository<DisclosureEvent, UUID> {

    List<DisclosureEvent> findByLoanIdOrderByOccurredAtDescIdDesc(UUID loanId);
}
