package com.msfg.los.documents.service;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.platform.text.HtmlText;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

@Component
public class PreApprovalLetterGenerator {

    public String generate(Loan loan, String primaryBorrowerName) {
        String borrowerDisplay = primaryBorrowerName != null ? primaryBorrowerName : "Valued Borrower";
        String loanNumber = loan.getLoanNumber() != null ? loan.getLoanNumber() : "N/A";

        BigDecimal amount = loan.getBaseLoanAmount();
        String formattedAmount = amount != null
                ? NumberFormat.getCurrencyInstance(Locale.US).format(amount)
                : "Amount Pending";

        var prop = loan.getSubjectProperty();
        String propertyLocation = "";
        if (prop != null) {
            String city = prop.getCity();
            String state = prop.getState();
            if (city != null && state != null) {
                propertyLocation = city + ", " + state;
            } else if (city != null) {
                propertyLocation = city;
            } else if (state != null) {
                propertyLocation = state;
            }
        }
        if (propertyLocation.isEmpty()) {
            propertyLocation = "Subject Property";
        }

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8"/>
                  <title>Pre-Approval Letter — Loan %s</title>
                  <style>
                    body { font-family: Arial, sans-serif; margin: 40px; color: #333; }
                    .header { text-align: center; margin-bottom: 32px; }
                    .header h1 { font-size: 28px; color: #1a3a6b; margin: 0; }
                    .header p { color: #666; margin: 4px 0 0; }
                    .letter { max-width: 700px; margin: 0 auto; line-height: 1.6; }
                    .field { margin: 8px 0; }
                    .footer { margin-top: 48px; border-top: 1px solid #ddd; padding-top: 16px; font-size: 12px; color: #888; }
                  </style>
                </head>
                <body>
                  <div class="letter">
                    <div class="header">
                      <h1>MSFG</h1>
                      <p>Mortgage &amp; Financial Group</p>
                    </div>
                    <h2>Pre-Approval Letter</h2>
                    <div class="field"><strong>Loan Number:</strong> %s</div>
                    <div class="field"><strong>Borrower:</strong> %s</div>
                    <div class="field"><strong>Pre-Approved Loan Amount:</strong> %s</div>
                    <div class="field"><strong>Subject Property:</strong> %s</div>
                    <p>
                      This letter confirms that the above-named borrower has been pre-approved for a mortgage
                      loan in the amount stated above, subject to satisfactory appraisal, title search,
                      verification of income and assets, and final underwriting approval.
                    </p>
                    <p>
                      This pre-approval is valid for 90 days from the date of issuance and is subject to
                      change based on any material changes in the borrower's financial condition or applicable
                      lending guidelines.
                    </p>
                    <p>
                      Please contact your loan officer for additional information or questions regarding
                      your pre-approval.
                    </p>
                    <div class="footer">
                      <p>MSFG — Mortgage &amp; Financial Group | This is not a commitment to lend.</p>
                      <p>Loan Reference: %s</p>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(loanNumber, loanNumber, HtmlText.escape(borrowerDisplay),
                formattedAmount, HtmlText.escape(propertyLocation), loanNumber);
    }
}
