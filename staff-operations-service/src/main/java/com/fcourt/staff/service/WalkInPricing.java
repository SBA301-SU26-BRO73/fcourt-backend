package com.fcourt.staff.service;

import com.fcourt.staff.integration.AvailabilityGateway.Slot;
import com.fcourt.staff.integration.BookingGateway;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class WalkInPricing {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");
    private final BigDecimal fixed;
    private final BigDecimal percent;
    private final String policyVersion;

    public WalkInPricing(@Value("${staff.pricing.fixed-surcharge:0}") BigDecimal fixed,
                          @Value("${staff.pricing.surcharge-percent:0}") BigDecimal percent,
                          @Value("${staff.pricing.policy-version:counter-v1}") String policyVersion) {
        if (fixed.signum() < 0 || fixed.scale() > 2 || fixed.compareTo(MAX_AMOUNT) > 0
                || percent.signum() < 0 || percent.compareTo(new BigDecimal("100")) > 0
                || percent.scale() > 4 || policyVersion.isBlank() || policyVersion.length() > 64) {
            throw new IllegalArgumentException("Invalid walk-in pricing policy");
        }
        this.fixed = fixed; this.percent = percent; this.policyVersion = policyVersion;
    }

    public BookingGateway.CashQuote quote(List<Slot> selected) {
        var sorted = selected.stream().sorted(Comparator.comparing(Slot::startTime)).toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (!sorted.get(i - 1).endTime().equals(sorted.get(i).startTime())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Walk-in slots must be contiguous");
            }
        }
        BigDecimal base = selected.stream().map(Slot::price).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal surcharge = fixed.add(base.multiply(percent).movePointLeft(2)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = base.add(surcharge).setScale(2, RoundingMode.UNNECESSARY);
        if (total.compareTo(MAX_AMOUNT) > 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cash total exceeds supported amount");
        return new BookingGateway.CashQuote(base, surcharge, total, policyVersion, sorted.stream()
                .map(slot -> new BookingGateway.ChargedSlot(slot.slotId(), slot.startTime(), slot.endTime(), slot.price())).toList());
    }
}
