package com.fcourt.voucher;

/** Normalizes Vietnamese phone numbers to local digit form (0xxxxxxxxx) for storage and prefix search. */
final class VoucherPhone {
    static final int MIN_SEARCH_DIGITS = 3;
    static final int MAX_DIGITS = 15;
    private VoucherPhone() {}

    /** Digits only; a leading country code 84 becomes 0. Returns null when empty or longer than MAX_DIGITS. */
    static String digits(String raw) {
        if (raw == null) return null;
        var out = new StringBuilder();
        for (int i = 0; i < raw.length() && out.length() <= MAX_DIGITS + 2; i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') out.append(c);
        }
        String digits = out.toString();
        if (digits.startsWith("84") && digits.length() >= 3) digits = "0" + digits.substring(2);
        return digits.isEmpty() || digits.length() > MAX_DIGITS ? null : digits;
    }

    /** Value kept for search: only plausible full numbers (9..15 digits). */
    static String storable(String raw) {
        String digits = digits(raw);
        return digits != null && digits.length() >= 9 ? digits : null;
    }
}
