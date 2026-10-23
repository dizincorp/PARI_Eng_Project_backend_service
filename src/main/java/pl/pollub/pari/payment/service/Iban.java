package pl.pollub.pari.payment.service;

import java.math.BigInteger;
import java.util.Locale;

/** IBAN syntax check (country code, check digits, ISO 7064 mod 97). It does not check that the account exists. */
final class Iban {

    private Iban() {
    }

    /** Returns the IBAN without spaces in upper case, or throws {@link IllegalArgumentException}. */
    static String normalize(String input) {
        String iban = input == null ? "" : input.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (!iban.matches("[A-Z]{2}\\d{2}[A-Z0-9]{11,30}") || !hasValidChecksum(iban)) {
            throw new IllegalArgumentException("The IBAN is not valid");
        }
        return iban;
    }

    private static boolean hasValidChecksum(String iban) {
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            digits.append(Character.digit(c, 36));
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }
}
