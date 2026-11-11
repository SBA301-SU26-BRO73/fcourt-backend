package com.fcourt.auth.account;

import com.fcourt.auth.account.AccountDtos.AuthResponse;
import com.fcourt.auth.account.AccountDtos.LoginRequest;
import com.fcourt.auth.account.AccountDtos.RegisterCourtOwnerRequest;
import com.fcourt.auth.account.AccountDtos.RegisterCustomerRequest;
import com.fcourt.auth.account.AccountDtos.RegistrationResponse;
import com.fcourt.auth.account.AccountDtos.UpdateProfileRequest;
import com.fcourt.auth.account.AccountDtos.UserResponse;
import com.fcourt.auth.account.AccountRepository.AccountRecord;
import com.fcourt.auth.common.ApiException;
import com.fcourt.auth.outbox.OutboxWriter;
import com.fcourt.auth.user.UserRole;
import com.fcourt.auth.user.UserStatus;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * Registration, login and profile. Customers register ACTIVE and get a session straight away; court
 * owners (role ADMIN) register PENDING_APPROVAL with their documents and wait for a super admin (SCRUM-8).
 */
@Service
public class AuthService {
    static final String LEGAL = "LEGAL";
    static final String COURT_IMAGE = "COURT_IMAGE";
    static final int MAX_LEGAL_DOCUMENTS = 5;
    static final int MAX_COURT_IMAGES = 10;
    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "application/pdf");

    private final AccountRepository accounts;
    private final SessionService sessions;
    private final PasswordEncoder passwordEncoder;
    private final DocumentStorage storage;
    private final FailedAttemptThrottle throttle;
    private final Clock clock;
    private final OutboxWriter outbox;
    /** Verified against when the email is unknown, so login timing does not reveal which emails exist. */
    private final String timingEqualiserHash;

    AuthService(AccountRepository accounts, SessionService sessions, PasswordEncoder passwordEncoder,
                DocumentStorage storage, FailedAttemptThrottle throttle, Clock clock, OutboxWriter outbox) {
        this.outbox = outbox;
        this.accounts = accounts;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.storage = storage;
        this.throttle = throttle;
        this.clock = clock;
        this.timingEqualiserHash = passwordEncoder.encode("timing-equaliser");
    }

    @Transactional
    public AuthResponse registerCustomer(RegisterCustomerRequest request) {
        var account = createAccount(request.email(), request.password(), request.fullName(), request.phone(),
                UserRole.CUSTOMER, UserStatus.ACTIVE);
        return sessions.open(account);
    }

    @Transactional
    public RegistrationResponse registerCourtOwner(RegisterCourtOwnerRequest request) {
        List<MultipartFile> legal = nullToEmpty(request.legalDocuments());
        List<MultipartFile> images = nullToEmpty(request.courtImages());
        validateFiles(legal, "legalDocuments", 1, MAX_LEGAL_DOCUMENTS);
        validateFiles(images, "courtImages", 0, MAX_COURT_IMAGES);

        var account = createAccount(request.email(), request.password(), request.fullName(), request.phone(),
                UserRole.ADMIN, UserStatus.PENDING_APPROVAL);
        List<String> uploaded = deleteUploadsIfRolledBack();
        saveDocuments(account.id(), legal, LEGAL, "owner-documents/legal/" + account.id(), uploaded);
        saveDocuments(account.id(), images, COURT_IMAGE, "owner-documents/court/" + account.id(), uploaded);
        // Same transaction as the account: the event exists if and only if the registration committed.
        outbox.append(account.id(), OwnerRegisteredEvent.TYPE, OwnerRegisteredEvent.VERSION,
                new OwnerRegisteredEvent(account.id(), account.email(), account.fullName(), clock.instant(),
                        accounts.activeSuperAdminIds()));
        return new RegistrationResponse(account.id(), account.status(),
                "Registration submitted. You can sign in once a super admin approves it.");
    }

    /**
     * Counts failures per email, known or not, so the 429 cannot be used to find registered emails.
     * Not transactional: the failure count must commit although the login fails.
     */
    public AuthResponse login(LoginRequest request) {
        String email = normaliseEmail(request.email());
        String attemptKey = FailedAttemptThrottle.loginKey(email);
        throttle.checkAllowed(attemptKey);
        var account = accounts.findByEmail(email).orElse(null);
        boolean matches = passwordEncoder.matches(request.password(),
                account == null ? timingEqualiserHash : account.passwordHash());
        if (account == null || !matches) {
            throttle.recordFailure(attemptKey);
            throw ApiException.unauthorized("Invalid email or password");
        }
        throttle.recordSuccess(attemptKey);
        // Status is only revealed after the password proved who is asking.
        AccountPolicy.requireActive(account);
        return sessions.open(account);
    }

    @Transactional(readOnly = true)
    public UserResponse profile(long userId) {
        var account = activeAccount(userId);
        return withAvatar(toResponse(account), storage.publicUrl(accounts.avatarUrl(userId)));
    }

    @Transactional
    public UserResponse updateProfile(long userId, UpdateProfileRequest request) {
        var account = activeAccount(userId);
        String phone = request.phone();
        if (accounts.phoneInUse(phone, userId)) {
            throw ApiException.conflict("Phone number is already registered");
        }
        String fullName = request.fullName().strip();
        try {
            accounts.updateProfile(userId, fullName, phone, clock.instant());
        } catch (DuplicateKeyException race) {
            throw ApiException.conflict("Phone number is already registered");
        }
        return withAvatar(toResponse(new AccountRecord(account.id(), account.email(), account.passwordHash(), fullName, phone,
                account.role(), account.status(), account.createdAt())), storage.publicUrl(accounts.avatarUrl(userId)));
    }

    private AccountRecord activeAccount(long userId) {
        var account = accounts.findById(userId).orElseThrow(() -> ApiException.notFound("Account not found"));
        AccountPolicy.requireActive(account);
        return account;
    }

    private AccountRecord createAccount(String rawEmail, String password, String fullName, String phone,
                                        UserRole role, UserStatus status) {
        String email = normaliseEmail(rawEmail);
        if (accounts.emailExists(email)) {
            throw ApiException.conflict("Email is already registered");
        }
        if (accounts.phoneInUse(phone, 0)) {
            throw ApiException.conflict("Phone number is already registered");
        }
        long id;
        try {
            id = accounts.insert(email, passwordEncoder.encode(password), fullName.strip(), phone,
                    role.name(), status.name(), clock.instant());
        } catch (DuplicateKeyException race) {
            // Lost a race against a concurrent registration with the same email or phone.
            throw ApiException.conflict("Email or phone number is already registered");
        }
        return accounts.findById(id).orElseThrow(() -> new IllegalStateException("Account vanished after insert"));
    }

    private void validateFiles(List<MultipartFile> files, String field, int min, int max) {
        if (files.size() < min) {
            throw ApiException.badRequest(field + " is required");
        }
        if (files.size() > max) {
            throw ApiException.badRequest(field + " accepts at most " + max + " files");
        }
        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                throw ApiException.badRequest(field + " contains an empty file");
            }
            if (file.getSize() > MAX_FILE_BYTES) {
                throw ApiException.badRequest(field + " files must be at most 5 MB");
            }
            if (file.getContentType() == null || !ALLOWED_TYPES.contains(file.getContentType())) {
                throw ApiException.badRequest(field + " accepts only JPEG, PNG, WebP or PDF files");
            }
        }
    }

    /**
     * Files reach S3 before the registration commits. If the transaction rolls back (a later upload
     * or insert fails, or the commit itself), the files already stored are deleted again, best effort.
     */
    private List<String> deleteUploadsIfRolledBack() {
        List<String> uploaded = new ArrayList<>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    uploaded.forEach(storage::delete);
                }
            }
        });
        return uploaded;
    }

    private void saveDocuments(long userId, List<MultipartFile> files, String docType, String folder,
                               List<String> uploaded) {
        for (MultipartFile file : files) {
            byte[] content;
            try {
                content = file.getBytes();
            } catch (IOException error) {
                throw ApiException.badRequest("Uploaded file could not be read");
            }
            String key = storage.storePrivate(content, folder);
            uploaded.add(key);
            accounts.insertDocument(userId, key, docType, clock.instant());
        }
    }

    private static UserResponse withAvatar(UserResponse user, String avatarUrl) {
        return new UserResponse(user.id(), user.email(), user.fullName(), user.phone(), user.role(), user.status(),
                user.createdAt(), avatarUrl);
    }

    static UserResponse toResponse(AccountRecord account) {
        return new UserResponse(account.id(), account.email(), account.fullName(), account.phone(),
                account.role(), account.status(), account.createdAt());
    }

    private static String normaliseEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
