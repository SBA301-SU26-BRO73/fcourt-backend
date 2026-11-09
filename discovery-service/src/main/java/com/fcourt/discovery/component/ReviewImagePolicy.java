package com.fcourt.discovery.component;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Decides which image URLs a review may store, independent of the storage provider: the URL must start with the
 * configured public base (HTTPS; plain HTTP only on localhost for LocalStack), and its key part must be {@code reviews/{callerId}/...}, so only files this service
 * uploaded for that user are accepted. Existing rows are never re-validated.
 */
public final class ReviewImagePolicy {
    private final URI base;
    private final String keyPrefix;

    /** @param publicBaseUrl HTTPS base of every issued URL (empty or non-HTTPS means not configured)
     *  @param keyPrefix folder the provider adds in front of the key inside the URL (may be empty) */
    public ReviewImagePolicy(String publicBaseUrl, String keyPrefix) {
        URI parsed = null;
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            try {
                String text = publicBaseUrl.trim();
                URI candidate = URI.create(text.endsWith("/") ? text : text + "/");
                if (secureOrLoopback(candidate)
                        && candidate.getRawUserInfo() == null && candidate.getRawQuery() == null && candidate.getRawFragment() == null) {
                    parsed = candidate;
                }
            } catch (IllegalArgumentException error) { parsed = null; }
        }
        this.base = parsed;
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
    }

    /** HTTPS without a port, or plain HTTP on a loopback host (LocalStack in local development only). */
    private static boolean secureOrLoopback(URI uri) {
        if (uri.getHost() == null) return false;
        if ("https".equalsIgnoreCase(uri.getScheme())) return uri.getPort() == -1;
        return "http".equalsIgnoreCase(uri.getScheme()) && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
    }

    public boolean configured() { return base != null; }

    /** Key space of one user's review photos; derived server-side, never from client input. */
    public static String userPrefix(long userId) { return "reviews/" + userId + "/"; }

    /** Fresh provider-neutral key for an upload, e.g. {@code reviews/10/<uuid>.jpg}. */
    public static String newKey(long userId, String extension) { return userPrefix(userId) + UUID.randomUUID() + "." + extension; }

    public boolean allows(String url, long userId) {
        if (base == null || url == null) return false;
        URI uri;
        try { uri = URI.create(url); } catch (IllegalArgumentException error) { return false; }
        if (!base.getScheme().equalsIgnoreCase(uri.getScheme()) || !base.getHost().equalsIgnoreCase(uri.getHost()) || uri.getPort() != base.getPort()
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) return false;
        String path = uri.getRawPath();
        // No percent-encoding, dot segments or empty segments: nothing that could escape the expected key space.
        if (path == null || path.contains("%") || path.contains("..") || path.contains("//") || path.contains("\\")) return false;
        String basePath = base.getRawPath();
        if (!path.startsWith(basePath)) return false;
        String rest = path.substring(basePath.length());
        // Optional version segment (v123) some hosts put before the key.
        if (rest.matches("v\\d+/.*")) rest = rest.substring(rest.indexOf('/') + 1);
        if (!keyPrefix.isEmpty()) {
            if (!rest.startsWith(keyPrefix)) return false;
            rest = rest.substring(keyPrefix.length());
        }
        String prefix = userPrefix(userId);
        return rest.startsWith(prefix) && rest.length() > prefix.length();
    }

    public boolean allowsAll(List<String> urls, long userId) { return urls.stream().allMatch(url -> allows(url, userId)); }
}
