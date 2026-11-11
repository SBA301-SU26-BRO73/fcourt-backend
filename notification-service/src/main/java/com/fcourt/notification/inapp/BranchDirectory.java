package com.fcourt.notification.inapp;

import java.util.List;
import java.util.OptionalLong;

/**
 * Who works at a branch, resolved through the services that own that data (events only carry the branch id).
 */
public interface BranchDirectory {
    /**
     * The court owner (ADMIN) of the branch, from facility-service. Empty when facility-service says the branch
     * does not exist; a facility-service outage throws so the event is retried instead of lost.
     */
    OptionalLong ownerOf(long branchId);

    /**
     * Active staff user ids of the branch, from staff-operations-service. Best effort: when the directory is not
     * configured (no token) or unreachable the list is empty and the owner is still notified.
     */
    List<Long> staffOf(long branchId);
}
