package com.fcourt.auth.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.auth.account.SessionService;
import jakarta.validation.Validation;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

class StaffAccountCommandsTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ManagedStaffBoundary boundary = mock(ManagedStaffBoundary.class);
    private final SessionService sessions = mock(SessionService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private StaffAccounts accounts;
    private StaffReactivation reactivation;
    private final Jwt admin = Jwt.withTokenValue("token").header("alg", "none").subject("1").build();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wire() {
        doAnswer(call -> {
            long id = call.getArgument(2);
            return List.of(switch ((int) id) {
                case 1 -> new StaffReactivation.Account(1, "owner@fixture.test", "Owner", "0912345678", "ADMIN", "ACTIVE");
                default -> new StaffReactivation.Account(id, "staff@fixture.test", "Staff", "0912345679", "STAFF", "ACTIVE");
            });
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        accounts = new StaffAccounts(jdbc, boundary, new ObjectMapper(), sessions, transactions);
        reactivation = new StaffReactivation(jdbc, boundary, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                transactions);
    }

    private static String key() {
        return "staff:1:" + UUID.randomUUID();
    }

    @Test
    void disableRevokesEveryRefreshTokenOfTheStaffAccount() {
        accounts.disable(admin, 101, key());

        verify(sessions).closeAll(101);
    }

    @Test
    void ownershipIsCheckedOverHttpBeforeAnyTransactionOpens() {
        accounts.disable(admin, 101, key());

        InOrder order = inOrder(boundary, transactions);
        order.verify(boundary).require(101, 0, "token");
        order.verify(transactions).getTransaction(any());
    }

    @Test
    void reactivationChecksOwnershipOverHttpBeforeAnyTransactionOpens() {
        reactivation.reactivate(admin, 101, 7, key());

        InOrder order = inOrder(boundary, transactions);
        order.verify(boundary).require(101, 7, "token");
        order.verify(transactions).getTransaction(any());
    }

    @Test
    void concurrentRegistrationOfTheSameEmailIsAConflictNotAServerError() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenThrow(new DuplicateKeyException("users_email_key"));
        var request = new StaffAccounts.Create(7, "staff@fixture.test", "StrongPassword123", "Staff", "0912345679");

        assertThatThrownBy(() -> accounts.create(admin, key(), request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void staffPhoneMustBeAValidVietnameseNumberLikeEveryOtherAccount() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String phone : List.of("912345678", "+1234567890", "0123")) {
                assertThat(validator.validate(new StaffAccounts.Update(7, "Staff", phone))).as(phone).isNotEmpty();
                assertThat(validator.validate(
                        new StaffAccounts.Create(7, "a@b.test", "StrongPassword123", "Staff", phone))).as(phone)
                        .isNotEmpty();
            }
            for (String phone : List.of("0912345678", "+84912345678")) {
                assertThat(validator.validate(new StaffAccounts.Update(7, "Staff", phone))).as(phone).isEmpty();
            }
        }
    }
}
