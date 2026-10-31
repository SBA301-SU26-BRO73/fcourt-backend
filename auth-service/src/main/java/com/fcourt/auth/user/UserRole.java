package com.fcourt.auth.user;

import java.util.Arrays;

/** Account roles stored in {@code users.role}; ADMIN is a court owner. */
public enum UserRole {
    CUSTOMER, STAFF, ADMIN, SUPER_ADMIN;

    public static boolean isKnown(String value) {
        return Arrays.stream(values()).anyMatch(role -> role.name().equals(value));
    }
}
