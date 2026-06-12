package com.msfg.los.aus.service;

/**
 * Port for ordering (or reissuing) a credit report from a credit vendor.
 *
 * <p>Production-adapter contract (real-wire mapping):
 *
 * <p>Real adapters speak MISMO 2.x credit-request XML over HTTPS POST (Xactus-style
 * operatorId/password auth); creditReportIdentifier is the bureau-assigned reference that
 * travels into DU/LPA reissue; REISSUE requires it, SUBMIT/FORCE_NEW mint it.
 */
public interface CreditVendorPort {

    CreditVendorResult order(CreditVendorRequest request);
}
