package com.fcourt.discovery.component;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Privacy-safe public display name: given name (last word) plus the family-name initial, e.g. "An N.".
 * Words that are not plain name words (digits, '@', URLs, symbols) are dropped, so an email or phone typed into the
 * name field is never published; control and zero-width characters are removed first.
 */
public final class ReviewerNames {
    /** Control characters (tab, newline...) separate words; invisible format characters (zero-width, bidi) vanish. */
    private static final Pattern CONTROL = Pattern.compile("\\p{Cc}");
    private static final Pattern FORMAT = Pattern.compile("\\p{Cf}");
    private static final Pattern SEPARATORS = Pattern.compile("[\\p{Z}\\s]+");
    private static final Pattern NAME_WORD = Pattern.compile("[\\p{L}\\p{M}'’.-]+");
    private static final int MAX_GIVEN = 30;

    private ReviewerNames() {}

    /** Returns null when there is no usable name, so the API can fall back to a generic label. */
    public static String mask(String fullName) {
        if (fullName == null) return null;
        String[] words = Arrays.stream(SEPARATORS.split(FORMAT.matcher(CONTROL.matcher(fullName).replaceAll(" ")).replaceAll("").strip()))
                .filter(word -> NAME_WORD.matcher(word).matches() && word.codePoints().anyMatch(Character::isLetter))
                .toArray(String[]::new);
        if (words.length == 0) return null;
        String given = clip(words[words.length - 1]);
        if (words.length == 1) return given;
        int initial = words[0].codePoints().filter(Character::isLetter).findFirst().orElseThrow();
        return given + " " + new String(Character.toChars(initial)).toUpperCase(Locale.ROOT) + ".";
    }

    private static String clip(String word) {
        return word.codePointCount(0, word.length()) <= MAX_GIVEN ? word : word.substring(0, word.offsetByCodePoints(0, MAX_GIVEN));
    }
}
