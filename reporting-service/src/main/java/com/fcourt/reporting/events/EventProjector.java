package com.fcourt.reporting.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.reporting.events.EventTranslator.BookingFact;
import com.fcourt.reporting.events.EventTranslator.Ignored;
import com.fcourt.reporting.persistence.ReadModelStore;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies one event to the read model atomically with its dedupe marker. */
@Service
public class EventProjector {
    private static final Logger log = LoggerFactory.getLogger(EventProjector.class);
    private final EventTranslator translator;
    private final ReadModelStore store;
    private final ObjectMapper json;

    public EventProjector(EventTranslator translator, ReadModelStore store, ObjectMapper json) {
        this.translator = translator;
        this.store = store;
        this.json = json;
    }

    @Transactional
    public boolean bookingEvent(String topic, String payload, Instant recordTime, boolean fromPayment) {
        Object result = fromPayment ? translator.payment(payload, recordTime) : translator.staff(payload, recordTime);
        if (result instanceof Ignored ignored) {
            store.markProcessed(ignored.eventId(), topic, ignored.type());
            return false;
        }
        BookingFact fact = (BookingFact) result;
        if (!store.markProcessed(fact.eventId(), topic, fact.type())) {
            log.debug("Duplicate event {} ignored", fact.eventId());
            return false;
        }
        store.apply(fact);
        return true;
    }

    /** facility.branch-updated.v1 has no event id; the per-branch version is the dedupe/ordering guard. */
    @Transactional
    public void branchSnapshot(String payload) {
        JsonNode root;
        try {
            root = json.readTree(payload);
        } catch (JsonProcessingException error) {
            throw new InvalidEventException("Branch event is not valid JSON");
        }
        if (root == null || !root.path("branchId").canConvertToLong() || root.path("branchId").asLong() <= 0
                || !root.path("version").canConvertToLong() || root.path("version").asLong() <= 0
                || !root.path("active").isBoolean() || !root.path("deleted").isBoolean()
                || !root.path("courts").isArray()) {
            throw new InvalidEventException("Invalid branch snapshot");
        }
        boolean active = root.get("active").asBoolean();
        boolean deleted = root.get("deleted").asBoolean();
        int courts = active && !deleted ? root.get("courts").size() : 0;
        store.applyBranchSnapshot(root.get("branchId").asLong(), root.get("version").asLong(), active, deleted, courts);
    }
}
