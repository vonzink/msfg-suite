package com.msfg.los.contacts.repo;

import com.msfg.los.contacts.domain.Contact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContactRepository extends JpaRepository<Contact, UUID> {
    List<Contact> findByLoanIdOrderByOrdinalAscIdAsc(UUID loanId);
    Optional<Contact> findByIdAndOrgId(UUID id, UUID orgId);
    Optional<Contact> findTopByLoanIdOrderByOrdinalDesc(UUID loanId);
}
