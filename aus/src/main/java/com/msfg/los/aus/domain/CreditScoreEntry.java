package com.msfg.los.aus.domain;

import java.util.UUID;

/** One bureau score for one borrower. Stored in the jsonb scores column on CreditOrder. */
public record CreditScoreEntry(UUID borrowerId, CreditBureau bureau, Integer score, String model) {}
