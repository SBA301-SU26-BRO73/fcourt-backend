package com.fcourt.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.discovery.messaging.BranchEventListener;
import com.fcourt.discovery.messaging.BranchUpdatedEvent;
import com.fcourt.discovery.persistence.BranchProjection;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BranchEventListenerTest {
    @Test
    void validatesBeforeProjecting() throws Exception {
        var projection = mock(BranchProjection.class);
        var mapper = new ObjectMapper();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var listener = new BranchEventListener(mapper, factory.getValidator(), projection);
            var valid = new BranchUpdatedEvent(1L, 1L, "Name", "Address", "City", null,
                    null, null, true, false, List.of(new BranchUpdatedEvent.CourtSnapshot(1L, 1L, null, null)));
            listener.consume(mapper.writeValueAsString(valid));
            verify(projection).apply(valid);
            clearInvocations(projection);
            for (String invalid : List.of("null", "{}", "{broken",
                    mapper.writeValueAsString(new BranchUpdatedEvent(1L, 1L, "Name", "Address", "City", null,
                            10.0, null, true, false, List.of())),
                    mapper.writeValueAsString(new BranchUpdatedEvent(1L, 1L, "Name", "Address", "City", null,
                            null, null, true, false, List.of(valid.courts().getFirst(), valid.courts().getFirst()))))) {
                assertThatThrownBy(() -> listener.consume(invalid)).isInstanceOf(Exception.class);
            }
            verifyNoInteractions(projection);
        }
    }
}
