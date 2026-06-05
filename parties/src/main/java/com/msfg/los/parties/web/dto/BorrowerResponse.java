package com.msfg.los.parties.web.dto;
import com.msfg.los.parties.domain.BorrowerParty;
import com.msfg.los.parties.domain.CitizenshipType;
import com.msfg.los.parties.domain.MaritalStatus;
import com.msfg.los.platform.pii.SsnSupport;
import java.time.LocalDate;
import java.util.UUID;
public record BorrowerResponse(
    UUID id, UUID loanId, boolean primary, int ordinal, String firstName, String lastName,
    String middleName, String suffix, String ssnLast4, String ssnMasked, LocalDate dateOfBirth,
    MaritalStatus maritalStatus, Integer dependentsCount, String dependentAges, CitizenshipType citizenshipType,
    Boolean veteran, Boolean unmarriedAddendumSpousalRights, UUID joinedToBorrowerId,
    String homePhone, String cellPhone, String workPhone, String workPhoneExt, String email, Boolean noEmail) {
    public static BorrowerResponse from(BorrowerParty b) {
        return new BorrowerResponse(b.getId(), b.getLoanId(), b.isPrimary(), b.getOrdinal(),
            b.getFirstName(), b.getLastName(), b.getMiddleName(), b.getSuffix(),
            SsnSupport.last4(b.getSsn()), SsnSupport.maskedDisplay(b.getSsn()), b.getDateOfBirth(),
            b.getMaritalStatus(), b.getDependentsCount(), b.getDependentAges(), b.getCitizenshipType(),
            b.getVeteran(), b.getUnmarriedAddendumSpousalRights(), b.getJoinedToBorrowerId(),
            b.getHomePhone(), b.getCellPhone(), b.getWorkPhone(), b.getWorkPhoneExt(), b.getEmail(), b.getNoEmail());
    }
}
