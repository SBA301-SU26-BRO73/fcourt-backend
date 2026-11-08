package com.fcourt.discovery.component;

import java.util.Locale;

/** Privacy-safe public display name: given name (last word) plus the family-name initial, e.g. "An N.". */
public final class ReviewerNames {
    private ReviewerNames() {}

    /** Returns null when there is no usable name, so the API can fall back to a generic label. */
    public static String mask(String fullName) {
        if (fullName == null) return null;
        String[] words = fullName.strip().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) return null;
        String given = clip(words[words.length - 1]);
        if (words.length == 1) return given;
        int initial = words[0].codePointAt(0);
        if (!Character.isLetter(initial)) return given;
        return given + " " + new String(Character.toChars(initial)).toUpperCase(Locale.ROOT) + ".";
    }

    private static String clip(String word) {
        return word.codePointCount(0, word.length()) <= 30 ? word : word.substring(0, word.offsetByCodePoints(0, 30));
    }
}
