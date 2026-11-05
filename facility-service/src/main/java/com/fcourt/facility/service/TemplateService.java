package com.fcourt.facility.service;

import com.fcourt.facility.api.BranchResponse;
import com.fcourt.facility.api.TemplateDtos.ApplyResponse;
import com.fcourt.facility.api.TemplateDtos.ApplyTemplateRequest;
import com.fcourt.facility.api.TemplateDtos.BulkAppliedCourt;
import com.fcourt.facility.api.TemplateDtos.BulkApplyRequest;
import com.fcourt.facility.api.TemplateDtos.BulkApplyResponse;
import com.fcourt.facility.api.TemplateDtos.BulkConflict;
import com.fcourt.facility.api.TemplateDtos.BulkPreviewCourt;
import com.fcourt.facility.api.TemplateDtos.BulkPreviewResponse;
import com.fcourt.facility.api.TemplateDtos.PreviewResponse;
import com.fcourt.facility.api.TemplateDtos.PreviewSlot;
import com.fcourt.facility.api.TemplateDtos.ScheduleSlot;
import com.fcourt.facility.api.TemplateDtos.SingleTemplateRequest;
import com.fcourt.facility.api.TemplateDtos.SlotSchedule;
import com.fcourt.facility.api.TemplateDtos.TemplateSlot;
import com.fcourt.facility.events.BranchEventWriter;
import com.fcourt.facility.persistence.BranchStore;
import com.fcourt.facility.persistence.CourtStore;
import com.fcourt.facility.persistence.CourtStore.OwnedCourt;
import com.fcourt.facility.persistence.TemplateStore;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.server.ResponseStatusException;

/** 30-minute time slot templates per court and weekday (SCRUM-18). Sunday = 0 .. Saturday = 6. */
@Service
public class TemplateService {
    private static final List<Integer> ALL_DAYS = IntStream.rangeClosed(0, 6).boxed().toList();

    private final BranchStore branches;
    private final CourtStore courts;
    private final TemplateStore templates;
    private final BranchEventWriter events;
    private final CourtService courtService;
    private final TransactionTemplate tx;

    public TemplateService(BranchStore branches, CourtStore courts, TemplateStore templates, BranchEventWriter events,
                           CourtService courtService, TransactionTemplate tx) {
        this.branches = branches;
        this.courts = courts;
        this.templates = templates;
        this.events = events;
        this.courtService = courtService;
        this.tx = tx;
    }

    record Plan(List<PreviewSlot> slots, List<Integer> days) { }

    public PreviewResponse preview(long adminId, long courtId, ApplyTemplateRequest request) {
        OwnedCourt court = courtService.owned(adminId, courtId);
        Plan plan = plan(court, branch(court), request);
        return new PreviewResponse(courtId, plan.slots(), plan.slots().size());
    }

    public ApplyResponse apply(long adminId, long courtId, ApplyTemplateRequest request) {
        long branchId = courtService.owned(adminId, courtId).court().branchId();
        return tx.execute(status -> {
            if (!branches.lockOwned(branchId, adminId)) throw CourtService.notFound();
            OwnedCourt court = courtService.owned(adminId, courtId);
            Plan plan = plan(court, branch(court), request);
            List<TemplateSlot> created = new ArrayList<>(plan.slots().size() * plan.days().size());
            for (int day : plan.days()) {
                templates.retireDay(courtId, day);
                for (PreviewSlot slot : plan.slots()) {
                    created.add(templates.insert(courtId, day, slot.startTime(), slot.endTime(), slot.price()));
                }
            }
            events.branchChanged(branchId);
            return new ApplyResponse(courtId, plan.days(), created.size(), created);
        });
    }

    /**
     * Dry run of a bulk apply (SCRUM-51). Unknown or foreign courts are a 404 for the whole request;
     * courts that cannot take the template are reported per court instead of failing the preview.
     */
    public BulkPreviewResponse bulkPreview(Map<Long, Long> ownerByCourt, BulkApplyRequest request) {
        requireValidRange(request.template());
        List<BulkPreviewCourt> result = new ArrayList<>();
        int total = 0;
        boolean conflicts = false;
        for (long courtId : request.courtIds()) {
            OwnedCourt court = courtService.owned(ownerByCourt.get(courtId), courtId);
            try {
                Plan plan = plan(court, branch(court), request.template());
                int count = plan.slots().size() * plan.days().size();
                total += count;
                result.add(new BulkPreviewCourt(courtId, court.court().name(), court.court().branchId(), plan.days(),
                        plan.slots(), count, null));
            } catch (ResponseStatusException invalid) {
                if (invalid.getStatusCode() != HttpStatus.BAD_REQUEST) throw invalid;
                conflicts = true;
                result.add(new BulkPreviewCourt(courtId, court.court().name(), court.court().branchId(), List.of(),
                        List.of(), 0, invalid.getReason()));
            }
        }
        return new BulkPreviewResponse(result, total, conflicts);
    }

    /**
     * Applies one template to every court in a single transaction (SCRUM-51). An unknown/foreign court (404) or
     * any court that cannot take the template (409 listing every conflict) aborts and rolls back everything.
     */
    public BulkApplyResponse bulkApply(Map<Long, Long> ownerByCourt, BulkApplyRequest request) {
        requireValidRange(request.template());
        // Only to learn which branches to lock; every court is re-read after the locks are held.
        Map<Long, Long> branchOwners = new TreeMap<>();
        for (long courtId : request.courtIds()) {
            long owner = ownerByCourt.get(courtId);
            branchOwners.put(courtService.owned(owner, courtId).court().branchId(), owner);
        }
        return tx.execute(status -> {
            // TreeMap = ascending branch id, so concurrent bulk requests cannot deadlock on each other.
            branchOwners.forEach((branchId, owner) -> {
                if (!branches.lockOwned(branchId, owner)) throw CourtService.notFound();
            });
            List<BulkAppliedCourt> applied = new ArrayList<>();
            List<BulkConflict> conflicts = new ArrayList<>();
            int total = 0;
            for (long courtId : request.courtIds()) {
                OwnedCourt court = courtService.owned(ownerByCourt.get(courtId), courtId);
                Plan plan;
                try {
                    plan = plan(court, branch(court), request.template());
                } catch (ResponseStatusException invalid) {
                    if (invalid.getStatusCode() != HttpStatus.BAD_REQUEST) throw invalid;
                    conflicts.add(new BulkConflict(courtId, invalid.getReason()));
                    continue;
                }
                if (!conflicts.isEmpty()) continue; // already doomed: only keep collecting conflicts
                for (int day : plan.days()) {
                    templates.retireDay(courtId, day);
                    for (PreviewSlot slot : plan.slots()) {
                        templates.insert(courtId, day, slot.startTime(), slot.endTime(), slot.price());
                    }
                }
                int count = plan.slots().size() * plan.days().size();
                total += count;
                applied.add(new BulkAppliedCourt(courtId, court.court().name(), plan.days(), plan.slots().size(), count));
            }
            if (!conflicts.isEmpty()) throw bulkConflict(conflicts); // rolls back every court written so far
            branchOwners.keySet().forEach(events::branchChanged);
            return new BulkApplyResponse(applied, total);
        });
    }

    /** Rejects a request that is wrong whatever the court is, so it is a 400 and not a per-court conflict. */
    private static void requireValidRange(ApplyTemplateRequest request) {
        try {
            int duration = request.durationMinutes() == null ? SlotTemplateEngine.SLOT_MINUTES : request.durationMinutes();
            if (request.startTime() != null && request.endTime() != null) {
                SlotTemplateEngine.split(request.startTime(), request.endTime(), duration);
            } else if (duration != SlotTemplateEngine.SLOT_MINUTES) {
                throw new IllegalArgumentException("durationMinutes must be " + SlotTemplateEngine.SLOT_MINUTES);
            }
        } catch (IllegalArgumentException invalid) {
            throw badRequest(invalid.getMessage());
        }
    }

    private static ErrorResponseException bulkConflict(List<BulkConflict> conflicts) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Template cannot be applied to " + conflicts.size() + " court(s); nothing was changed");
        problem.setProperty("conflicts", conflicts);
        return new ErrorResponseException(HttpStatus.CONFLICT, problem, null);
    }

    public List<TemplateSlot> list(long adminId, long courtId, Integer dayOfWeek) {
        if (dayOfWeek != null && (dayOfWeek < 0 || dayOfWeek > 6)) throw badRequest("dayOfWeek must be 0..6");
        courtService.owned(adminId, courtId);
        return templates.listLive(courtId, dayOfWeek);
    }

    public TemplateSlot createOne(long adminId, long courtId, SingleTemplateRequest request) {
        long branchId = courtService.owned(adminId, courtId).court().branchId();
        return tx.execute(status -> {
            if (!branches.lockOwned(branchId, adminId)) throw CourtService.notFound();
            OwnedCourt court = courtService.owned(adminId, courtId);
            BranchResponse branch = branch(court);
            if (!request.endTime().isAfter(request.startTime())) throw badRequest("endTime must be after startTime");
            if (Duration.between(request.startTime(), request.endTime()).toMinutes() != SlotTemplateEngine.SLOT_MINUTES
                    || request.startTime().getSecond() != 0) {
                throw badRequest("A template slot must last exactly " + SlotTemplateEngine.SLOT_MINUTES + " minutes");
            }
            requireWithinHours(branch, request.startTime(), request.endTime());
            if (templates.overlaps(courtId, request.dayOfWeek(), request.startTime(), request.endTime())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Slot overlaps an existing template slot");
            }
            TemplateSlot slot = templates.insert(courtId, request.dayOfWeek(), request.startTime(), request.endTime(),
                    request.price().setScale(2, java.math.RoundingMode.HALF_UP));
            events.branchChanged(branchId);
            return slot;
        });
    }

    public void delete(long adminId, long templateId) {
        long[] owner = templates.findOwner(templateId).filter(row -> row[2] == adminId).orElseThrow(TemplateService::notFound);
        long branchId = owner[1];
        tx.executeWithoutResult(status -> {
            if (!branches.lockOwned(branchId, adminId)) throw notFound();
            templates.findOwner(templateId).orElseThrow(TemplateService::notFound);
            templates.retire(templateId);
            events.branchChanged(branchId);
        });
    }

    /** Read model for discovery-service: GET /api/internal/courts/{courtId}/slot-schedule?date=. */
    public SlotSchedule schedule(long courtId, LocalDate date) {
        OwnedCourt court = courts.find(courtId).orElseThrow(CourtService::notFound);
        boolean outOfService = !"ACTIVE".equals(court.court().status()) || !"ACTIVE".equals(court.branchStatus());
        int dayOfWeek = date.getDayOfWeek().getValue() % 7; // java Monday=1..Sunday=7 -> Sunday=0
        List<ScheduleSlot> slots = templates.listLive(courtId, dayOfWeek).stream()
                .map(t -> new ScheduleSlot(t.id(), t.startTime(), t.endTime(), t.price(), outOfService)).toList();
        return new SlotSchedule(courtId, date, outOfService, slots);
    }

    private Plan plan(OwnedCourt court, BranchResponse branch, ApplyTemplateRequest request) {
        LocalTime start = request.startTime() == null ? branch.openTime() : request.startTime();
        LocalTime end = request.endTime() == null ? branch.closeTime() : request.endTime();
        int duration = request.durationMinutes() == null ? SlotTemplateEngine.SLOT_MINUTES : request.durationMinutes();
        try {
            if (!end.isAfter(start)) throw new IllegalArgumentException("endTime must be after startTime");
            requireWithinHours(branch, start, end);
            BigDecimal price = SlotTemplateEngine.slotPrice(request.price(), court.court().defaultPricePerHour(),
                    duration, request.priceMultiplier());
            List<PreviewSlot> slots = SlotTemplateEngine.split(start, end, duration).stream()
                    .map(block -> new PreviewSlot(block.start(), block.end(), price)).toList();
            List<Integer> days = request.daysOfWeek() == null ? ALL_DAYS
                    : request.daysOfWeek().stream().sorted().toList();
            return new Plan(slots, days);
        } catch (IllegalArgumentException invalid) {
            throw badRequest(invalid.getMessage());
        }
    }

    private static void requireWithinHours(BranchResponse branch, LocalTime start, LocalTime end) {
        if (start.isBefore(branch.openTime()) || end.isAfter(branch.closeTime())) {
            throw badRequest("Slots must be within branch hours " + branch.openTime() + "-" + branch.closeTime());
        }
    }

    private BranchResponse branch(OwnedCourt court) {
        return branches.find(court.court().branchId()).orElseThrow(CourtService::notFound);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Time slot template not found");
    }
}
