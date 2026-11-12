package com.fcourt.auth.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fcourt.auth.outbox.OutboxWriter;
import com.fcourt.auth.account.AccountDtos.AuthResponse;
import com.fcourt.auth.account.AccountDtos.LoginRequest;
import com.fcourt.auth.account.AccountDtos.RegisterCourtOwnerRequest;
import com.fcourt.auth.account.AccountDtos.RegisterCustomerRequest;
import com.fcourt.auth.account.AccountDtos.UpdateProfileRequest;
import com.fcourt.auth.account.AccountRepository.AccountRecord;
import com.fcourt.auth.common.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AuthServiceTest {
    static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    static final String PASSWORD = "S3cret-pass";
    // Plain encoder for fixtures: the service under test gets a spy, which must not be touched while stubbing.
    static final PasswordEncoder FIXTURE_HASHER = new BCryptPasswordEncoder(4);

    AccountRepository accounts;
    SessionService sessions;
    DocumentStorage storage;
    PasswordEncoder encoder;
    FailedAttemptThrottle throttle;
    OutboxWriter outbox;
    AuthService service;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        sessions = mock(SessionService.class);
        storage = mock(DocumentStorage.class);
        encoder = spy(new BCryptPasswordEncoder(4));
        throttle = mock(FailedAttemptThrottle.class);
        outbox = mock(OutboxWriter.class);
        service = new AuthService(accounts, sessions, encoder, storage, throttle, Clock.fixed(NOW, ZoneOffset.UTC), outbox);
        // registerCourtOwner is @Transactional and registers a rollback hook; emulate the transaction here.
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearSynchronization() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void customerRegistersActiveWithNormalisedEmailHashedPasswordAndGetsASession() {
        var created = account(7, "customer@demo.com", "CUSTOMER", "ACTIVE");
        var session = authResponse();
        when(accounts.insert(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(7L);
        when(accounts.findById(7)).thenReturn(Optional.of(created));
        when(sessions.open(created)).thenReturn(session);

        var response = service.registerCustomer(new RegisterCustomerRequest(
                " An Nguyen ", "  Customer@Demo.COM ", "0909000001", PASSWORD));

        assertThat(response).isSameAs(session);
        var hash = ArgumentCaptor.forClass(String.class);
        verify(accounts).insert(eq("customer@demo.com"), hash.capture(), eq("An Nguyen"), eq("0909000001"),
                eq("CUSTOMER"), eq("ACTIVE"), eq(NOW));
        assertThat(hash.getValue()).isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, hash.getValue())).isTrue();
    }

    @Test
    void registrationWithTakenEmailOrPhoneConflictsBeforeAnythingIsWritten() {
        when(accounts.emailExists("taken@demo.com")).thenReturn(true);
        when(accounts.phoneInUse("0909000002", 0)).thenReturn(true);

        assertThatThrownBy(() -> service.registerCustomer(customer("taken@demo.com", "0909000001")))
                .satisfies(error -> assertApi(error, HttpStatus.CONFLICT, "Email is already registered"));
        assertThatThrownBy(() -> service.registerCustomer(customer("new@demo.com", "0909000002")))
                .satisfies(error -> assertApi(error, HttpStatus.CONFLICT, "Phone number is already registered"));

        verify(accounts, never()).insert(any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(sessions);
    }

    @Test
    void concurrentRegistrationLosingTheRaceIsAConflictNotAServerError() {
        when(accounts.insert(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("users_email_key"));

        assertThatThrownBy(() -> service.registerCustomer(customer("race@demo.com", "0909000001")))
                .satisfies(error -> assertApi(error, HttpStatus.CONFLICT,
                        "Email or phone number is already registered"));
    }

    @Test
    void courtOwnerRegistersPendingWithDocumentsAndNoSession() {
        var created = account(9, "owner@demo.com", "ADMIN", "PENDING_APPROVAL");
        when(accounts.insert(any(), any(), any(), any(), any(), any(), any())).thenReturn(9L);
        when(accounts.findById(9)).thenReturn(Optional.of(created));
        when(storage.storePrivate(any(), eq("owner-documents/legal/9"))).thenReturn("owner-documents/legal/9/a.pdf");
        when(storage.storePrivate(any(), eq("owner-documents/court/9"))).thenReturn("owner-documents/court/9/b.jpg");

        var response = service.registerCourtOwner(owner(
                List.of(file("licence.pdf", "application/pdf", 10)), List.of(file("front.jpg", "image/jpeg", 10))));

        assertThat(response.id()).isEqualTo(9);
        assertThat(response.status()).isEqualTo("PENDING_APPROVAL");
        verify(accounts).insert(eq("owner@demo.com"), anyString(), eq("Chu San"), eq("0909000003"),
                eq("ADMIN"), eq("PENDING_APPROVAL"), eq(NOW));
        verify(accounts).insertDocument(9, "owner-documents/legal/9/a.pdf", "LEGAL", NOW);
        verify(accounts).insertDocument(9, "owner-documents/court/9/b.jpg", "COURT_IMAGE", NOW);
        verifyNoInteractions(sessions);
    }

    @Test
    void courtOwnerRegistrationTellsTheActiveSuperAdminsThroughTheOutbox() {
        var created = account(9, "owner@demo.com", "ADMIN", "PENDING_APPROVAL");
        when(accounts.insert(any(), any(), any(), any(), any(), any(), any())).thenReturn(9L);
        when(accounts.findById(9)).thenReturn(Optional.of(created));
        when(accounts.activeSuperAdminIds()).thenReturn(List.of(1L, 2L));
        when(storage.storePrivate(any(), anyString())).thenReturn("owner-documents/legal/9/a.png");

        service.registerCourtOwner(owner(List.of(file("id.png", "image/png", 10)), null));

        verify(outbox).append(9L, "OwnerRegisteredEvent", 1,
                new OwnerRegisteredEvent(9, "owner@demo.com", "Full Name", NOW, List.of(1L, 2L)));
    }

    @Test
    void courtOwnerCourtImagesAreOptional() {
        var created = account(9, "owner@demo.com", "ADMIN", "PENDING_APPROVAL");
        when(accounts.insert(any(), any(), any(), any(), any(), any(), any())).thenReturn(9L);
        when(accounts.findById(9)).thenReturn(Optional.of(created));
        when(storage.storePrivate(any(), anyString())).thenReturn("owner-documents/legal/9/a.png");

        service.registerCourtOwner(owner(List.of(file("id.png", "image/png", 10)), null));

        verify(accounts).insertDocument(eq(9L), anyString(), eq("LEGAL"), any());
        verify(accounts, never()).insertDocument(anyLong(), anyString(), eq("COURT_IMAGE"), any());
    }

    @Test
    void courtOwnerNeedsAtLeastOneLegalDocument() {
        assertThatThrownBy(() -> service.registerCourtOwner(owner(List.of(), null)))
                .satisfies(error -> assertApi(error, HttpStatus.BAD_REQUEST, "legalDocuments is required"));
        assertThatThrownBy(() -> service.registerCourtOwner(owner(null, null)))
                .satisfies(error -> assertApi(error, HttpStatus.BAD_REQUEST, "legalDocuments is required"));
        verify(accounts, never()).insert(any(), any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "application/zip"})
    void courtOwnerDocumentsMustBeImagesOrPdf(String contentType) {
        var request = owner(List.of(file("bad.bin", contentType, 10)), null);

        assertThatThrownBy(() -> service.registerCourtOwner(request))
                .satisfies(error -> assertApi(error, HttpStatus.BAD_REQUEST,
                        "legalDocuments accepts only JPEG, PNG, WebP or PDF files"));
        verify(accounts, never()).insert(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void courtOwnerFilesAreLimitedInSizeCountAndEmptiness() {
        var oversized = owner(List.of(file("big.pdf", "application/pdf", (int) AuthService.MAX_FILE_BYTES + 1)), null);
        var empty = owner(List.of(file("empty.pdf", "application/pdf", 0)), null);
        var tooMany = owner(java.util.Collections.nCopies(AuthService.MAX_LEGAL_DOCUMENTS + 1,
                file("a.pdf", "application/pdf", 10)), null);
        var tooManyImages = owner(List.of(file("a.pdf", "application/pdf", 10)),
                java.util.Collections.nCopies(AuthService.MAX_COURT_IMAGES + 1, file("a.jpg", "image/jpeg", 10)));

        assertThatThrownBy(() -> service.registerCourtOwner(oversized))
                .satisfies(error -> assertApi(error, HttpStatus.BAD_REQUEST, "legalDocuments files must be at most 5 MB"));
        assertThatThrownBy(() -> service.registerCourtOwner(empty))
                .satisfies(error -> assertApi(error, HttpStatus.BAD_REQUEST, "legalDocuments contains an empty file"));
        assertThatThrownBy(() -> service.registerCourtOwner(tooMany))
                .satisfies(error -> assertApi(error, HttpStatus.BAD_REQUEST, "legalDocuments accepts at most 5 files"));
        assertThatThrownBy(() -> service.registerCourtOwner(tooManyImages))
                .satisfies(error -> assertApi(error, HttpStatus.BAD_REQUEST, "courtImages accepts at most 10 files"));
        verifyNoInteractions(storage);
    }

    @Test
    void storageOutageSurfacesAsServiceUnavailable() {
        when(accounts.insert(any(), any(), any(), any(), any(), any(), any())).thenReturn(9L);
        when(accounts.findById(9)).thenReturn(Optional.of(account(9, "owner@demo.com", "ADMIN", "PENDING_APPROVAL")));
        when(storage.storePrivate(any(), anyString())).thenThrow(ApiException.unavailable("Document storage is unavailable"));

        assertThatThrownBy(() -> service.registerCourtOwner(owner(List.of(file("a.pdf", "application/pdf", 10)), null)))
                .satisfies(error -> assertApi(error, HttpStatus.SERVICE_UNAVAILABLE, "Document storage is unavailable"));
    }

    @Test
    void filesUploadedBeforeARollbackAreDeletedFromStorage() {
        when(accounts.insert(any(), any(), any(), any(), any(), any(), any())).thenReturn(9L);
        when(accounts.findById(9)).thenReturn(Optional.of(account(9, "owner@demo.com", "ADMIN", "PENDING_APPROVAL")));
        when(storage.storePrivate(any(), eq("owner-documents/legal/9"))).thenReturn("owner-documents/legal/9/a.pdf");
        when(storage.storePrivate(any(), eq("owner-documents/court/9"))).thenThrow(ApiException.unavailable("Document storage is unavailable"));

        assertThatThrownBy(() -> service.registerCourtOwner(owner(
                List.of(file("licence.pdf", "application/pdf", 10)), List.of(file("front.jpg", "image/jpeg", 10)))))
                .satisfies(error -> assertApi(error, HttpStatus.SERVICE_UNAVAILABLE, "Document storage is unavailable"));
        verify(storage, never()).delete(anyString());

        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(storage).delete("owner-documents/legal/9/a.pdf");
    }

    @Test
    void filesOfACommittedRegistrationAreKept() {
        when(accounts.insert(any(), any(), any(), any(), any(), any(), any())).thenReturn(9L);
        when(accounts.findById(9)).thenReturn(Optional.of(account(9, "owner@demo.com", "ADMIN", "PENDING_APPROVAL")));
        when(storage.storePrivate(any(), anyString())).thenReturn("owner-documents/legal/9/a.pdf");

        service.registerCourtOwner(owner(List.of(file("licence.pdf", "application/pdf", 10)), null));

        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void lockedLoginAnswers429BeforeThePasswordIsChecked() {
        org.mockito.Mockito.doThrow(new com.fcourt.auth.common.RetryLaterException(FailedAttemptThrottle.LOCKED,
                java.time.Duration.ofMinutes(10))).when(throttle).checkAllowed("login:customer@demo.com");

        assertThatThrownBy(() -> service.login(new LoginRequest(" Customer@Demo.com", PASSWORD)))
                .satisfies(error -> assertApi(error, HttpStatus.TOO_MANY_REQUESTS, FailedAttemptThrottle.LOCKED));
        verifyNoInteractions(accounts, sessions);
        verify(encoder, never()).matches(anyString(), anyString());
    }

    @Test
    void failedLoginsAreCountedPerEmailKnownOrNotAndASuccessClearsTheCount() {
        var active = accountWithPassword(7, "customer@demo.com", "CUSTOMER", "ACTIVE");
        when(accounts.findByEmail("customer@demo.com")).thenReturn(Optional.of(active));
        when(accounts.findByEmail("ghost@demo.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("customer@demo.com", "wrong-password")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.login(new LoginRequest("ghost@demo.com", PASSWORD)))
                .isInstanceOf(ApiException.class);
        service.login(new LoginRequest("customer@demo.com", PASSWORD));

        verify(throttle).recordFailure("login:customer@demo.com");
        verify(throttle).recordFailure("login:ghost@demo.com");
        verify(throttle).recordSuccess("login:customer@demo.com");
    }

    @Test
    void loginOpensASessionForAnActiveAccountWithTheRightPassword() {
        var active = accountWithPassword(7, "customer@demo.com", "CUSTOMER", "ACTIVE");
        var session = authResponse();
        when(accounts.findByEmail("customer@demo.com")).thenReturn(Optional.of(active));
        when(sessions.open(active)).thenReturn(session);

        assertThat(service.login(new LoginRequest("  Customer@Demo.com ", PASSWORD))).isSameAs(session);
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameAnswerAndBothRunTheHash() {
        when(accounts.findByEmail("customer@demo.com"))
                .thenReturn(Optional.of(accountWithPassword(7, "customer@demo.com", "CUSTOMER", "ACTIVE")));
        when(accounts.findByEmail("ghost@demo.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("customer@demo.com", "wrong-password")))
                .satisfies(error -> assertApi(error, HttpStatus.UNAUTHORIZED, "Invalid email or password"));
        assertThatThrownBy(() -> service.login(new LoginRequest("ghost@demo.com", PASSWORD)))
                .satisfies(error -> assertApi(error, HttpStatus.UNAUTHORIZED, "Invalid email or password"));

        // Once for the wrong password and once against the equaliser hash for the unknown email.
        verify(encoder, org.mockito.Mockito.times(2)).matches(anyString(), anyString());
        verifyNoInteractions(sessions);
    }

    @ParameterizedTest
    @CsvSource({"PENDING_APPROVAL,Account is awaiting approval", "REJECTED,Account registration was rejected",
            "LOCKED,Account is locked", "INACTIVE,Account is inactive"})
    void loginRefusesAccountsThatAreNotActive(String status, String detail) {
        when(accounts.findByEmail("owner@demo.com"))
                .thenReturn(Optional.of(accountWithPassword(9, "owner@demo.com", "ADMIN", status)));

        assertThatThrownBy(() -> service.login(new LoginRequest("owner@demo.com", PASSWORD)))
                .satisfies(error -> assertApi(error, HttpStatus.FORBIDDEN, detail));
        verifyNoInteractions(sessions);
    }

    @Test
    void rejectedOwnerLoginCarriesTheRejectionReason() {
        when(accounts.findByEmail("owner@demo.com"))
                .thenReturn(Optional.of(accountWithPassword(9, "owner@demo.com", "ADMIN", "REJECTED")));
        when(accounts.rejectionReason(9)).thenReturn(Optional.of("Documents are unreadable"));

        assertThatThrownBy(() -> service.login(new LoginRequest("owner@demo.com", PASSWORD)))
                .satisfies(error -> {
                    assertApi(error, HttpStatus.FORBIDDEN, "Account registration was rejected");
                    assertThat(((ApiException) error).properties()).containsEntry("reason", "Documents are unreadable");
                });
        verifyNoInteractions(sessions);
    }

    @Test
    void statusIsNotRevealedToSomeoneWhoDoesNotKnowThePassword() {
        when(accounts.findByEmail("owner@demo.com"))
                .thenReturn(Optional.of(accountWithPassword(9, "owner@demo.com", "ADMIN", "LOCKED")));

        assertThatThrownBy(() -> service.login(new LoginRequest("owner@demo.com", "wrong-password")))
                .satisfies(error -> assertApi(error, HttpStatus.UNAUTHORIZED, "Invalid email or password"));
    }

    @Test
    void profileReturnsTheCurrentUserWithoutThePasswordHash() {
        when(accounts.findById(7)).thenReturn(Optional.of(account(7, "customer@demo.com", "CUSTOMER", "ACTIVE")));

        var profile = service.profile(7);

        assertThat(profile.id()).isEqualTo(7);
        assertThat(profile.email()).isEqualTo("customer@demo.com");
        assertThat(profile.role()).isEqualTo("CUSTOMER");
    }

    @Test
    void profileCarriesTheAvatarWhenOneIsStoredAndNullOtherwise() {
        when(accounts.findById(7)).thenReturn(Optional.of(account(7, "customer@demo.com", "CUSTOMER", "ACTIVE")));
        assertThat(service.profile(7).avatarUrl()).isNull();

        when(storage.publicUrl(null)).thenReturn(null);
        when(storage.publicUrl("avatars/7/a.png")).thenReturn("https://cdn.example.test/avatars/7/a.png");
        when(accounts.avatarUrl(7)).thenReturn("avatars/7/a.png");
        assertThat(service.profile(7).avatarUrl()).isEqualTo("https://cdn.example.test/avatars/7/a.png");
    }

    @Test
    void profileOfMissingOrLockedAccountIsRefused() {
        when(accounts.findById(1)).thenReturn(Optional.empty());
        when(accounts.findById(2)).thenReturn(Optional.of(account(2, "locked@demo.com", "CUSTOMER", "LOCKED")));

        assertThatThrownBy(() -> service.profile(1)).satisfies(error -> assertApi(error, HttpStatus.NOT_FOUND,
                "Account not found"));
        assertThatThrownBy(() -> service.profile(2)).satisfies(error -> assertApi(error, HttpStatus.FORBIDDEN,
                "Account is locked"));
    }

    @Test
    void updateProfileStoresTrimmedNameAndPhoneAndReturnsThem() {
        when(accounts.findById(7)).thenReturn(Optional.of(account(7, "customer@demo.com", "CUSTOMER", "ACTIVE")));

        var updated = service.updateProfile(7, new UpdateProfileRequest("  New Name ", "0911222333"));

        verify(accounts).updateProfile(7, "New Name", "0911222333", NOW);
        assertThat(updated.fullName()).isEqualTo("New Name");
        assertThat(updated.phone()).isEqualTo("0911222333");
    }

    @Test
    void updateProfileRejectsAPhoneNumberAnotherAccountOwns() {
        when(accounts.findById(7)).thenReturn(Optional.of(account(7, "customer@demo.com", "CUSTOMER", "ACTIVE")));
        when(accounts.phoneInUse("0911222333", 7)).thenReturn(true);

        assertThatThrownBy(() -> service.updateProfile(7, new UpdateProfileRequest("Name", "0911222333")))
                .satisfies(error -> assertApi(error, HttpStatus.CONFLICT, "Phone number is already registered"));
        verify(accounts, never()).updateProfile(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void updateProfileTurnsAPhoneRaceIntoAConflict() {
        when(accounts.findById(7)).thenReturn(Optional.of(account(7, "customer@demo.com", "CUSTOMER", "ACTIVE")));
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uq_users_phone"))
                .when(accounts).updateProfile(anyLong(), anyString(), anyString(), any());

        assertThatThrownBy(() -> service.updateProfile(7, new UpdateProfileRequest("Name", "0911222333")))
                .satisfies(error -> assertApi(error, HttpStatus.CONFLICT, "Phone number is already registered"));
    }

    static void completeTransaction(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(status));
    }

    static void assertApi(Throwable error, HttpStatus status, String detail) {
        assertThat(error).isInstanceOfSatisfying(ApiException.class, api -> {
            assertThat(api.status()).isEqualTo(status);
            assertThat(api.getMessage()).isEqualTo(detail);
        });
    }

    static RegisterCustomerRequest customer(String email, String phone) {
        return new RegisterCustomerRequest("An Nguyen", email, phone, PASSWORD);
    }

    static RegisterCourtOwnerRequest owner(List<org.springframework.web.multipart.MultipartFile> legal,
                                           List<org.springframework.web.multipart.MultipartFile> images) {
        return new RegisterCourtOwnerRequest("Chu San", "Owner@Demo.com", "0909000003", PASSWORD, legal, images);
    }

    static MockMultipartFile file(String name, String contentType, int size) {
        return new MockMultipartFile("file", name, contentType, new byte[size]);
    }

    static AuthResponse authResponse() {
        return new AuthResponse("access", "refresh", "Bearer", 900, null);
    }

    static AccountRecord account(long id, String email, String role, String status) {
        return new AccountRecord(id, email, "hash", "Full Name", "0909000001", role, status,
                OffsetDateTime.parse("2026-10-01T00:00:00Z"));
    }

    static AccountRecord accountWithPassword(long id, String email, String role, String status) {
        return new AccountRecord(id, email, FIXTURE_HASHER.encode(PASSWORD), "Full Name", "0909000001", role, status,
                OffsetDateTime.parse("2026-10-01T00:00:00Z"));
    }
}
