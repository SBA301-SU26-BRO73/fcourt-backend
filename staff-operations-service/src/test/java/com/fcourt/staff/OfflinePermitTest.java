package com.fcourt.staff;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.staff.service.OfflinePermitCodec;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import org.springframework.web.server.ResponseStatusException;

class OfflinePermitTest {
    @Test void signatureDetectsAlterationWrongKeyAndMissingConfiguration() {
        var json=new ObjectMapper().findAndRegisterModules();
        var codec=new OfflinePermitCodec("fixture-offline-signing-key-not-production",json);
        var p=new OfflinePermitCodec.Permit(100,7,42,LocalDate.of(2026,11,1),"hash",
                Instant.parse("2026-11-01T00:00:00Z"),Instant.parse("2026-11-01T01:00:00Z"),Instant.parse("2026-11-01T00:00:00Z"));
        String token=codec.sign(p);assertThat(codec.verify(token)).isEqualTo(p);
        var rotated=new OfflinePermitCodec("other-fixture-key-not-production-123456",json);
        org.springframework.test.util.ReflectionTestUtils.setField(rotated,"previousKeys","fixture-offline-signing-key-not-production");
        assertThat(rotated.verify(token)).isEqualTo(p);
        assertThatThrownBy(()->codec.verify("x"+token)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->new OfflinePermitCodec("other-fixture-key-not-production-123456",json).verify(token)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->new OfflinePermitCodec("",json).sign(p)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->codec.verify("bad")).isInstanceOf(ResponseStatusException.class);
    }
}
