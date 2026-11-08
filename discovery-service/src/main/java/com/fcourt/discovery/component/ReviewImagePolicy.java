package com.fcourt.discovery.component;

import java.net.URI;
import java.util.List;

/**
 * Decides which image URLs a review may store: only files this service uploaded to our Cloudinary cloud,
 * in the caller's own folder. Existing rows are never re-validated.
 */
public final class ReviewImagePolicy {
    static final String HOST = "res.cloudinary.com";
    private final String cloudName;

    public ReviewImagePolicy(String cloudName) { this.cloudName = cloudName == null ? "" : cloudName.trim(); }

    public boolean configured() { return !cloudName.isEmpty(); }

    /** Folder used for a user's uploads; derived server-side, never from client input. */
    public static String folder(long userId) { return "fcourt/reviews/" + userId; }

    public boolean allows(String url, long userId) {
        if (!configured() || url == null) return false;
        URI uri;
        try { uri = URI.create(url); } catch (IllegalArgumentException error) { return false; }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !HOST.equalsIgnoreCase(uri.getHost()) || uri.getPort() != -1
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) return false;
        String path = uri.getRawPath();
        // No percent-encoding or dot segments: nothing that could escape the expected folder.
        if (path == null || path.contains("%") || path.contains("..") || path.contains("//") || path.contains("\\")) return false;
        String prefix = "/" + cloudName + "/image/upload/";
        if (!path.startsWith(prefix)) return false;
        String rest = path.substring(prefix.length());
        // Optional version segment (v123) precedes the folder.
        if (rest.matches("v\\d+/.*")) rest = rest.substring(rest.indexOf('/') + 1);
        String folder = folder(userId) + "/";
        return rest.startsWith(folder) && rest.length() > folder.length();
    }

    public boolean allowsAll(List<String> urls, long userId) { return urls.stream().allMatch(url -> allows(url, userId)); }
}
