package com.fcourt.discovery.service.impl;

import com.fcourt.discovery.component.SlotSourceClient;
import com.fcourt.discovery.component.SlotSourceClient.Schedule;
import com.fcourt.discovery.component.SlotSourceClient.Slot;
import com.fcourt.discovery.dto.response.CourtSlotResponse;
import com.fcourt.discovery.dto.response.CourtSlotResponse.Status;
import com.fcourt.discovery.dto.response.BlockingSlotResponse;
import com.fcourt.discovery.repository.SlotHoldRepository;
import com.fcourt.discovery.service.CourtSlotService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class CourtSlotServiceImpl implements CourtSlotService {
    private final SlotSourceClient source;
    private final SlotHoldRepository holds;
    private final Clock clock;

    public CourtSlotServiceImpl(SlotSourceClient source, SlotHoldRepository holds, Clock clock) {
        this.source = source;
        this.holds = holds;
        this.clock = clock.withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
    }

    @Override
    public List<CourtSlotResponse> find(long courtId, LocalDate date) {
        if (courtId <= 0 || date == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid court ID or date");
        }
        Schedule schedule = source.schedule(courtId, date);
        List<Slot> slots = validate(schedule, courtId, date);
        if (slots.isEmpty()) return List.of();
        var occupancy = source.occupancy(courtId, date);
        if (occupancy == null || !Long.valueOf(courtId).equals(occupancy.courtId())
                || !date.equals(occupancy.date()) || occupancy.bookedSlotIds() == null) {
            throw SlotSourceClient.unavailable();
        }
        var ids = slots.stream().map(Slot::slotId).toList();
        Set<Long> booked = new HashSet<>(occupancy.bookedSlotIds());
        if (booked.contains(null) || booked.size() != occupancy.bookedSlotIds().size()) {
            throw SlotSourceClient.unavailable();
        }
        Set<Long> held = holds.heldSlots(courtId, date, ids);
        // Capture after I/O so slots that expire during the calls cannot remain available.
        LocalDateTime now = LocalDateTime.now(clock);
        return slots.stream().map(slot -> {
            Status status = status(slot, schedule, date, now, booked, held);
            return new CourtSlotResponse(slot.slotId(), slot.startTime(), slot.endTime(), slot.price(), status,
                    status == Status.OUT_OF_SERVICE ? publicRestriction(slot.restriction()) : null);
        }).toList();
    }

    @Override
    public List<BlockingSlotResponse> blockingSlots(long courtId, LocalDate date) {
        if (courtId <= 0 || date == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid court ID or date");
        }
        Schedule schedule = source.schedule(courtId, date);
        List<Slot> slots = validate(schedule, courtId, date);
        if (slots.isEmpty()) return List.of();
        var occupancy = source.occupancy(courtId, date);
        if (occupancy == null || !Long.valueOf(courtId).equals(occupancy.courtId())
                || !date.equals(occupancy.date()) || occupancy.bookedSlotIds() == null) {
            throw SlotSourceClient.unavailable();
        }
        Set<Long> booked = new HashSet<>(occupancy.bookedSlotIds());
        if (booked.contains(null) || booked.size() != occupancy.bookedSlotIds().size()) {
            throw SlotSourceClient.unavailable();
        }
        var ids = slots.stream().map(Slot::slotId).toList();
        Set<Long> held = holds.heldSlots(courtId, date, ids);
        return slots.stream().filter(slot -> booked.contains(slot.slotId()) || held.contains(slot.slotId()))
                .map(slot -> new BlockingSlotResponse(slot.slotId(), slot.startTime(), slot.endTime(),
                        booked.contains(slot.slotId()) ? "BOOKED" : "HOLD"))
                .toList();
    }

    private static final Set<String> RESTRICTION_TYPES = Set.of("OUT_OF_SERVICE", "PRIVATE_EVENT");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{9,15}$");

    /**
     * Optional display metadata: kept only when it is entirely valid, so a malformed upstream value
     * never leaks partially and never changes the slot status (the slot stays OUT_OF_SERVICE).
     */
    private static CourtSlotResponse.Restriction publicRestriction(SlotSourceClient.Restriction restriction) {
        if (restriction == null || restriction.type() == null || !RESTRICTION_TYPES.contains(restriction.type())
                || tooLong(restriction.publicReason(), 500) || tooLong(restriction.contactName(), 100)
                || (restriction.contactPhone() != null && !PHONE.matcher(restriction.contactPhone()).matches())) {
            return null;
        }
        return new CourtSlotResponse.Restriction(restriction.type(), restriction.publicReason(),
                restriction.contactName(), restriction.contactPhone());
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }

    private Status status(Slot slot, Schedule schedule, LocalDate date, LocalDateTime now,
                          Set<Long> booked, Set<Long> held) {
        if (!date.atTime(slot.startTime()).isAfter(now)) return Status.EXPIRED;
        if (schedule.outOfService() || slot.outOfService()) return Status.OUT_OF_SERVICE;
        if (booked.contains(slot.slotId())) return Status.BOOKED;
        if (held.contains(slot.slotId())) return Status.HOLD;
        return Status.AVAILABLE;
    }

    private List<Slot> validate(Schedule schedule, long courtId, LocalDate date) {
        if (schedule == null || !Long.valueOf(courtId).equals(schedule.courtId())
                || !date.equals(schedule.date()) || schedule.outOfService() == null
                || schedule.slots() == null || schedule.slots().size() > 48) {
            throw SlotSourceClient.unavailable();
        }
        Set<Long> ids = new HashSet<>();
        for (Slot slot : schedule.slots()) {
            if (slot == null || slot.slotId() == null || slot.slotId() <= 0 || !ids.add(slot.slotId())
                    || slot.startTime() == null || slot.endTime() == null || slot.outOfService() == null
                    || slot.startTime().getSecond() != 0 || slot.startTime().getNano() != 0
                    || slot.endTime().getSecond() != 0 || slot.endTime().getNano() != 0
                    || slot.price() == null || slot.price().signum() < 0 || slot.price().scale() > 2
                    || !duration(slot).equals(Duration.ofMinutes(30))) {
                throw SlotSourceClient.unavailable();
            }
        }
        var sorted = schedule.slots().stream().sorted(Comparator.comparing(Slot::startTime)).toList();
        for (int index = 1; index < sorted.size(); index++) {
            if (sorted.get(index).startTime().isBefore(sorted.get(index - 1).endTime())) {
                throw SlotSourceClient.unavailable();
            }
        }
        return sorted;
    }

    private Duration duration(Slot slot) {
        var duration = Duration.between(slot.startTime(), slot.endTime());
        return slot.endTime().equals(LocalTime.MIDNIGHT) ? duration.plusDays(1) : duration;
    }
}
