package com.msfg.los.platform.pii;
import com.msfg.los.platform.error.ValidationException;

public final class SsnSupport {
    private SsnSupport() {}
    public static String normalize(String input) {
        if (input == null) throw new ValidationException("SSN is required");
        String trimmed = input.trim();
        // Accept: 9 digits, dashed NNN-NN-NNNN, or space-separated NNN NN NNNN
        if (!trimmed.matches("\\d{9}") &&
            !trimmed.matches("\\d{3}-\\d{2}-\\d{4}") &&
            !trimmed.matches("\\d{3}\\s\\d{2}\\s\\d{4}")) {
            throw new ValidationException("SSN must be 9 digits");
        }
        String d = trimmed.replaceAll("\\D", "");
        if (d.length() != 9) throw new ValidationException("SSN must be 9 digits");
        String area = d.substring(0, 3), group = d.substring(3, 5), serial = d.substring(5);
        if (area.equals("000") || area.equals("666") || area.charAt(0) == '9')
            throw new ValidationException("Invalid SSN area number");
        if (group.equals("00")) throw new ValidationException("Invalid SSN group number");
        if (serial.equals("0000")) throw new ValidationException("Invalid SSN serial number");
        return d;
    }
    public static String last4(String ssn9) { return ssn9 == null ? null : ssn9.substring(ssn9.length() - 4); }
    public static String maskedDisplay(String ssn9) { return ssn9 == null ? null : "•••-••-" + last4(ssn9); }
    public static String formatDashed(String ssn9) {
        return ssn9 == null ? null : ssn9.substring(0,3) + "-" + ssn9.substring(3,5) + "-" + ssn9.substring(5);
    }
}
