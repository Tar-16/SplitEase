package com.example.splitease.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/** Shared account normalization and signup validation, independent of Android/storage. */
public final class AccountValidation {
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+");
    private AccountValidation() {}
    public static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    public static boolean validEmail(String value) { return EMAIL.matcher(normalize(value)).matches(); }
    /** length, uppercase, lowercase, digit, non-whitespace special character. */
    public static boolean[] passwordChecklist(char[] password) {
        boolean[] checks = new boolean[5];
        if (password == null) return checks;
        checks[0] = password.length >= 8;
        for (char c : password) {
            checks[1] |= Character.isUpperCase(c);
            checks[2] |= Character.isLowerCase(c);
            checks[3] |= Character.isDigit(c);
            checks[4] |= !Character.isLetterOrDigit(c) && !Character.isWhitespace(c);
        }
        return checks;
    }
    public static boolean strongPassword(char[] password) {
        for (boolean check : passwordChecklist(password)) if (!check) return false;
        return true;
    }
}
