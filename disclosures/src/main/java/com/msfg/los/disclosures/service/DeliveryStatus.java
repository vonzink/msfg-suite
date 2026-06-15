package com.msfg.los.disclosures.service;

import com.msfg.los.disclosures.domain.DisclosureStatus;
import java.time.LocalDate;

public record DeliveryStatus(DisclosureStatus status, LocalDate actualReceiptDate) {}
