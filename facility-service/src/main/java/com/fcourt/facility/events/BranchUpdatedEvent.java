package com.fcourt.facility.events;

import java.math.BigDecimal;
import java.util.List;

/**
 * Value of topic {@code facility.branch-updated.v1}, mirroring discovery-service's
 * {@code model/event/BranchUpdatedEvent}: a full public snapshot with every ACTIVE,
 * non-deleted court. Bank and owner data are intentionally absent.
 */
public record BranchUpdatedEvent(long branchId, long version, String name, String address, String city,
                                 String district, Double latitude, Double longitude, boolean active,
                                 boolean deleted, List<CourtSnapshot> courts) {

    public record CourtSnapshot(long id, long courtTypeId, BigDecimal minPrice, BigDecimal maxPrice) { }
}
