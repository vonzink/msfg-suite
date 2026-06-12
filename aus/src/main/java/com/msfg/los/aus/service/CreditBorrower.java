package com.msfg.los.aus.service;

import java.util.UUID;

/** Borrower identity included on a credit order. */
public record CreditBorrower(UUID borrowerId, String firstName, String lastName) {}
