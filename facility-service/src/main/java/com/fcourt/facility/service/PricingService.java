package com.fcourt.facility.service;

import com.fcourt.facility.api.PricingDtos.Quote;
import com.fcourt.facility.api.PricingDtos.QuoteSlot;
import com.fcourt.facility.api.PricingDtos.RuleRequest;
import com.fcourt.facility.api.PricingDtos.RuleResponse;
import com.fcourt.facility.api.TemplateDtos.TemplateSlot;
import com.fcourt.facility.events.BranchEventWriter;
import com.fcourt.facility.persistence.BranchStore;
import com.fcourt.facility.persistence.CourtStore;
import com.fcourt.facility.persistence.CourtStore.OwnedCourt;
import com.fcourt.facility.persistence.PricingRuleStore;
import com.fcourt.facility.persistence.TemplateStore;
import com.fcourt.facility.service.PricingEngine.Priced;
import com.fcourt.facility.service.PricingEngine.Rule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Peak/off-peak and weekday/weekend pricing rules of a court (SCRUM-80, SCRUM-82).
 * Rule times are aligned to the 30-minute slot grid, so a slot is never split between two rules
 * and a booking that crosses a rule boundary is priced slot by slot.
 */
@Service
public class PricingService {
    private static final Set<Integer> ALL_DAYS = new TreeSet<>(IntStream.rangeClosed(0, 6).boxed().toList());
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999999.99");

    private final BranchStore branches;
    private final CourtStore courts;
    private final TemplateStore templates;
    private final PricingRuleStore rules;
    private final BranchEventWriter events;
    private final CourtService courtService;
    private final TransactionTemplate tx;

    public PricingService(BranchStore branches, CourtStore courts, TemplateStore templates, PricingRuleStore rules,
                          BranchEventWriter events, CourtService courtService, TransactionTemplate tx) {
        this.branches = branches;
        this.courts = courts;
        this.templates = templates;
        this.rules = rules;
        this.events = events;
        this.courtService = courtService;
        this.tx = tx;
    }

    public List<RuleResponse> list(long adminId, long courtId) {
        courtService.owned(adminId, courtId);
        return rules.listLive(courtId).stream().map(rule -> response(courtId, rule)).toList();
    }

    public RuleResponse create(long adminId, long courtId, RuleRequest request) {
        long branchId = courtService.owned(adminId, courtId).court().branchId();
        Validated valid = validate(request);
        return tx.execute(status -> {
            lock(branchId, adminId, courtId);
            requireNoConflict(rules.listLive(courtId), probe(request, valid, -1));
            Rule created = rules.insert(courtId, request, valid.days(), valid.peak(), valid.price());
            events.branchChanged(branchId);
            return response(courtId, created);
        });
    }

    public RuleResponse update(long adminId, long courtId, long ruleId, RuleRequest request) {
        long branchId = courtService.owned(adminId, courtId).court().branchId();
        Validated valid = validate(request);
        return tx.execute(status -> {
            lock(branchId, adminId, courtId);
            rules.findLive(courtId, ruleId).orElseThrow(PricingService::notFound);
            List<Rule> others = rules.listLive(courtId).stream().filter(rule -> rule.id() != ruleId).toList();
            requireNoConflict(others, probe(request, valid, ruleId));
            Rule updated = rules.update(ruleId, request, valid.days(), valid.peak(), valid.price());
            events.branchChanged(branchId);
            return response(courtId, updated);
        });
    }

    public void delete(long adminId, long courtId, long ruleId) {
        long branchId = courtService.owned(adminId, courtId).court().branchId();
        tx.executeWithoutResult(status -> {
            lock(branchId, adminId, courtId);
            rules.findLive(courtId, ruleId).orElseThrow(PricingService::notFound);
            rules.retire(ruleId);
            events.branchChanged(branchId);
        });
    }

    /**
     * Replaces every weekend rule (Saturday/Sunday only) by a copy of each weekday rule (Monday-Friday only),
     * with the price multiplied by {@code multiplier} (default 1). Atomic: one transaction, one event.
     */
    public List<RuleResponse> copyWeekdayToWeekend(long adminId, long courtId, BigDecimal multiplier) {
        long branchId = courtService.owned(adminId, courtId).court().branchId();
        BigDecimal factor = multiplier == null ? BigDecimal.ONE : multiplier;
        return tx.execute(status -> {
            lock(branchId, adminId, courtId);
            List<Rule> live = rules.listLive(courtId);
            List<Rule> source = live.stream().filter(rule -> rule.dayType() == PricingEngine.DayType.WEEKDAY).toList();
            if (source.isEmpty()) throw badRequest("There are no weekday pricing rules to copy");
            List<BigDecimal> prices = source.stream().map(rule -> checkedPrice(
                    rule.pricePerHour().multiply(factor).setScale(2, RoundingMode.HALF_UP))).toList();
            List<Rule> weekendCopies = source.stream().map(rule -> new Rule(rule.id(), rule.name(),
                    rule.start(), rule.end(), rule.pricePerHour(), rule.peak(),
                    new TreeSet<>(PricingEngine.WEEKEND_DAYS))).toList();
            for (int i = 0; i < weekendCopies.size(); i++) {
                requireNoConflict(weekendCopies.subList(0, i), weekendCopies.get(i));
            }
            live.stream().filter(rule -> rule.dayType() == PricingEngine.DayType.WEEKEND)
                    .forEach(rule -> rules.retire(rule.id()));
            List<RuleResponse> created = new ArrayList<>();
            for (int i = 0; i < source.size(); i++) {
                created.add(response(courtId, rules.insertCopy(courtId, source.get(i),
                        new TreeSet<>(PricingEngine.WEEKEND_DAYS), prices.get(i))));
            }
            events.branchChanged(branchId);
            return created;
        });
    }

    /** Price of [start, end) on a date, slot by slot, for the booking flow (internal, read only). */
    public Quote quote(long courtId, LocalDate date, LocalTime start, LocalTime end) {
        OwnedCourt court = courts.find(courtId).orElseThrow(CourtService::notFound);
        if (!"ACTIVE".equals(court.court().status()) || !"ACTIVE".equals(court.branchStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Court is out of service");
        }
        requireGrid(start, end);
        int day = PricingEngine.dayOfWeek(date);
        List<TemplateSlot> live = templates.listLive(courtId, day);
        List<Rule> courtRules = rules.listLive(courtId);
        List<QuoteSlot> slots = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (LocalTime cursor = start; cursor.isBefore(end);
                cursor = cursor.plusMinutes(SlotTemplateEngine.SLOT_MINUTES)) {
            LocalTime from = cursor;
            LocalTime to = cursor.plusMinutes(SlotTemplateEngine.SLOT_MINUTES);
            TemplateSlot template = live.stream().filter(t -> t.startTime().equals(from) && t.endTime().equals(to))
                    .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "No time slot exists at " + from + " on " + date));
            Priced priced = PricingEngine.price(courtRules, day, from, to, template.price());
            slots.add(new QuoteSlot(from, to, priced.price(), priced.source(),
                    priced.rule() == null ? null : priced.rule().id()));
            total = total.add(priced.price());
        }
        return new Quote(courtId, date, total, slots);
    }

    private void lock(long branchId, long adminId, long courtId) {
        if (!branches.lockOwned(branchId, adminId)) throw CourtService.notFound();
        courtService.owned(adminId, courtId);
    }

    record Validated(Set<Integer> days, boolean peak, BigDecimal price) { }

    static Validated validate(RuleRequest request) {
        requireGrid(request.startTime(), request.endTime());
        Set<Integer> days = request.daysOfWeek() == null ? ALL_DAYS : new TreeSet<>(request.daysOfWeek());
        return new Validated(days, Boolean.TRUE.equals(request.peakHour()),
                checkedPrice(request.pricePerHour().setScale(2, RoundingMode.HALF_UP)));
    }

    private static void requireGrid(LocalTime start, LocalTime end) {
        if (start == null || end == null) throw badRequest("startTime and endTime are required");
        if (!end.isAfter(start)) throw badRequest("endTime must be after startTime");
        for (LocalTime time : List.of(start, end)) {
            if (time.getSecond() != 0 || time.getNano() != 0
                    || time.getMinute() % SlotTemplateEngine.SLOT_MINUTES != 0) {
                throw badRequest("Times must be aligned to " + SlotTemplateEngine.SLOT_MINUTES + "-minute slots");
            }
        }
    }

    private static BigDecimal checkedPrice(BigDecimal price) {
        if (price.signum() <= 0) throw badRequest("pricePerHour must be greater than 0");
        if (price.compareTo(MAX_PRICE) > 0) throw badRequest("pricePerHour must be at most " + MAX_PRICE);
        return price;
    }

    private static Rule probe(RuleRequest request, Validated valid, long id) {
        return new Rule(id, request.ruleName(), request.startTime(), request.endTime(), valid.price(),
                valid.peak(), valid.days());
    }

    private static void requireNoConflict(List<Rule> existing, Rule candidate) {
        existing.stream().filter(rule -> PricingEngine.conflicts(rule, candidate)).findFirst().ifPresent(rule -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Time range overlaps pricing rule \"" + rule.name() + "\" on a shared day");
        });
    }

    private static RuleResponse response(long courtId, Rule rule) {
        return new RuleResponse(rule.id(), courtId, rule.name(), rule.start(), rule.end(), rule.pricePerHour(),
                rule.peak(), List.copyOf(rule.days()), rule.dayType());
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Pricing rule not found");
    }
}
