package com.msfg.los.aus.service;

import java.util.List;

/** Per-borrower credit-report wiring carried into the AUS submission (reissue identifiers etc.). */
public record CreditWiring(String creditProviderCode, String creditAffiliateCode, List<BorrowerCredit> perBorrower) {}
