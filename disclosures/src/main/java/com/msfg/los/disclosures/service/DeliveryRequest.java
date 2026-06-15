package com.msfg.los.disclosures.service;

import com.msfg.los.disclosures.domain.DeliveryMethod;
import com.msfg.los.disclosures.domain.DisclosureKind;
import java.util.UUID;

public record DeliveryRequest(UUID loanId, UUID disclosureId, DisclosureKind kind, DeliveryMethod method) {}
