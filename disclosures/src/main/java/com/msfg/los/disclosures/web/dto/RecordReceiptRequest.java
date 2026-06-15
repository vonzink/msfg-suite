package com.msfg.los.disclosures.web.dto;

import java.time.LocalDate;

/** Request to record actual consumer receipt of an issued disclosure. */
public record RecordReceiptRequest(LocalDate receivedAt) {}
