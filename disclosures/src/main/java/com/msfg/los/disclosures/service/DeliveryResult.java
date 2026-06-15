package com.msfg.los.disclosures.service;

import com.msfg.los.disclosures.domain.ReceivedBasis;

public record DeliveryResult(String vendorReference, ReceivedBasis basis) {}
