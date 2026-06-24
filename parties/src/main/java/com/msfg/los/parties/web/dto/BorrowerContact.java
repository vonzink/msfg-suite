package com.msfg.los.parties.web.dto;

/**
 * Cross-module read seam result: a borrower's dispatch contacts (security spec §6.2). Carries ONLY
 * the non-NPI contact fields ({@code email}, {@code cellPhone}) needed to send a verification code —
 * never SSN/DOB or other NPI. Either field may be {@code null} if absent on the borrower row.
 */
public record BorrowerContact(String email, String cellPhone) {
}
