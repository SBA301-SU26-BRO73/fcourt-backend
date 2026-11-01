package com.fcourt.subscription.common;

import java.util.Arrays;

/** Roles issued by auth-service in the {@code roles} claim; ADMIN is a court owner. */
public enum Role {
    CUSTOMER, STAFF, ADMIN, SUPER_ADMIN;

    public static boolean isKnown(String value) {
        return Arrays.stream(values()).anyMatch(role -> role.name().equals(value));
    }
}
