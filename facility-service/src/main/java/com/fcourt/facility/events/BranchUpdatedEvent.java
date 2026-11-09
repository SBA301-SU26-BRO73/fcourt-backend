package com.fcourt.facility.events;

import java.math.BigDecimal;
import java.util.List;

/**
 * Value of topic {@code facility.branch-updated.v1}, mirroring discovery-service's
 * {@code model/event/BranchUpdatedEvent}: a full public snapshot with every ACTIVE,
 * non-deleted court. imageUrl is the cover photo for list cards
 * (first gallery image, else the first court image); openTime/closeTime are "HH:mm". Bank and owner data are intentionally absent.
 */
public record BranchUpdatedEvent(long branchId, long version, String name, String address, String city,
                                 String district, Double latitude, Double longitude, boolean active,
                                 boolean deleted, List<CourtSnapshot> courts,
                                 String imageUrl, String openTime, String closeTime) {

    /** Same snapshot without the card fields (kept for callers that do not know them). */
    public BranchUpdatedEvent(long branchId, long version, String name, String address, String city,
                              String district, Double latitude, Double longitude, boolean active,
                              boolean deleted, List<CourtSnapshot> courts) {
        this(branchId, version, name, address, city, district, latitude, longitude, active, deleted, courts,
                null, null, null);
    }

    public record CourtSnapshot(long id, long courtTypeId, BigDecimal minPrice, BigDecimal maxPrice) { }
}
