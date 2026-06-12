package com.msfg.los.aus.service;

/**
 * Port for submitting a loan casefile to an Automated Underwriting System vendor.
 *
 * <p>Production-adapter contract (real-wire mapping):
 *
 * <p>Real DU adapter: HTTPS POST of MISMO v3.4 ULAD XML in the Fannie Mae DU Wrapper schema;
 * credentials = Technology Manager user/password + institution id; vendorCaseId = DU loan
 * casefile ID (assigned at first submission, reused on resubmit, never across loans); findings
 * come back as codified XML + HTML.
 *
 * <p>Real LPA adapter: S2S v6.1 (lpa.xsd/ulad.xsd), OAuth tokens (~4h), seller/servicer + TPO
 * numbers; vendorCaseId = LPA Key, vendorTransactionId = transaction number; artifact =
 * Feedback Certificate.
 */
public interface AusVendorPort {

    AusVendorResult submit(AusSubmission submission);
}
