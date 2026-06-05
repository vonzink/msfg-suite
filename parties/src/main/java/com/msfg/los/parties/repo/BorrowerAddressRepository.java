package com.msfg.los.parties.repo;

import com.msfg.los.parties.domain.AddressType;
import com.msfg.los.parties.domain.BorrowerAddress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BorrowerAddressRepository extends JpaRepository<BorrowerAddress, UUID> {

    List<BorrowerAddress> findByBorrowerIdOrderByAddressTypeAscOrdinalAsc(UUID borrowerId);

    Optional<BorrowerAddress> findByIdAndOrgId(UUID id, UUID orgId);

    long countByBorrowerIdAndAddressType(UUID borrowerId, AddressType type);
}
