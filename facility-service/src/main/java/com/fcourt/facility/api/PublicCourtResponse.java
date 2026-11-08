package com.fcourt.facility.api;

import java.math.BigDecimal;

/** Customer-safe court fields for the public branch listing. */
public record PublicCourtResponse(long id, String name, long courtTypeId, BigDecimal defaultPricePerHour) { }
