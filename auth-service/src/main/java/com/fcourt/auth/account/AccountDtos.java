package com.fcourt.auth.account;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/** Request/response shapes of the public auth API. */
public final class AccountDtos {
    public static final String PHONE = "^(0|\\+84)\\d{8,10}$";
    static final String PHONE_MESSAGE = "phone must be a Vietnamese phone number such as 0909000001";
    // BCrypt only hashes the first 72 bytes, so longer passwords would silently weaken.
    static final int PASSWORD_MIN = 8;
    static final int PASSWORD_MAX = 72;
    static final String PASSWORD_BYTES_MESSAGE =
            "password is too long: at most 72 bytes, and accented letters take 2 to 3 bytes each";
    static final String NEW_PASSWORD_BYTES_MESSAGE =
            "newPassword is too long: at most 72 bytes, and accented letters take 2 to 3 bytes each";

    private AccountDtos() {
    }

    public record RegisterCustomerRequest(
            @NotBlank(message = "fullName is required")
            @Size(max = 150, message = "fullName must be at most 150 characters") String fullName,
            @NotBlank(message = "email is required") @Email(message = "email is invalid")
            @Size(max = 255, message = "email must be at most 255 characters") String email,
            @NotBlank(message = "phone is required") @Pattern(regexp = PHONE, message = PHONE_MESSAGE) String phone,
            @NotBlank(message = "password is required")
            @Size(min = PASSWORD_MIN, max = PASSWORD_MAX, message = "password must be 8 to 72 characters")
            @MaxUtf8Bytes(value = PASSWORD_MAX, message = PASSWORD_BYTES_MESSAGE) String password) {
    }

    /** Multipart form: {@code legalDocuments} (licence, identity) are required, {@code courtImages} optional. */
    public record RegisterCourtOwnerRequest(
            @NotBlank(message = "fullName is required")
            @Size(max = 150, message = "fullName must be at most 150 characters") String fullName,
            @NotBlank(message = "email is required") @Email(message = "email is invalid")
            @Size(max = 255, message = "email must be at most 255 characters") String email,
            @NotBlank(message = "phone is required") @Pattern(regexp = PHONE, message = PHONE_MESSAGE) String phone,
            @NotBlank(message = "password is required")
            @Size(min = PASSWORD_MIN, max = PASSWORD_MAX, message = "password must be 8 to 72 characters")
            @MaxUtf8Bytes(value = PASSWORD_MAX, message = PASSWORD_BYTES_MESSAGE) String password,
            @NotEmpty(message = "legalDocuments is required") List<MultipartFile> legalDocuments,
            List<MultipartFile> courtImages) {
    }

    public record LoginRequest(
            @NotBlank(message = "email is required") @Email(message = "email is invalid") String email,
            @NotBlank(message = "password is required") String password) {
    }

    public record RefreshRequest(@NotBlank(message = "refreshToken is required") String refreshToken) {
    }

    public record UpdateProfileRequest(
            @NotBlank(message = "fullName is required")
            @Size(max = 150, message = "fullName must be at most 150 characters") String fullName,
            @NotBlank(message = "phone is required") @Pattern(regexp = PHONE, message = PHONE_MESSAGE) String phone) {
    }

    public record ForgotPasswordRequest(
            @NotBlank(message = "email is required") @Email(message = "email is invalid") String email) {
    }

    public record ResetPasswordRequest(
            @NotBlank(message = "token is required") String token,
            @NotBlank(message = "newPassword is required")
            @Size(min = PASSWORD_MIN, max = PASSWORD_MAX, message = "newPassword must be 8 to 72 characters")
            @MaxUtf8Bytes(value = PASSWORD_MAX, message = NEW_PASSWORD_BYTES_MESSAGE) String newPassword) {
    }

    public record ChangePasswordRequest(
            @NotBlank(message = "currentPassword is required") String currentPassword,
            @NotBlank(message = "newPassword is required")
            @Size(min = PASSWORD_MIN, max = PASSWORD_MAX, message = "newPassword must be 8 to 72 characters")
            @MaxUtf8Bytes(value = PASSWORD_MAX, message = NEW_PASSWORD_BYTES_MESSAGE) String newPassword) {
    }

    public record MessageResponse(String message) {
    }

    public record UserResponse(long id, String email, String fullName, String phone, String role, String status,
                               OffsetDateTime createdAt, String avatarUrl) {
        /** Without an avatar: login/registration responses do not carry one, only the profile read does. */
        public UserResponse(long id, String email, String fullName, String phone, String role, String status,
                            OffsetDateTime createdAt) {
            this(id, email, fullName, phone, role, status, createdAt, null);
        }
    }

    /** {@code expiresIn} is the access token lifetime in seconds. */
    public record AuthResponse(String accessToken, String refreshToken, String tokenType, long expiresIn,
                               UserResponse user) {
    }

    /** Court owners cannot sign in until a super admin approves the registration. */
    public record RegistrationResponse(long id, String status, String message) {
    }
}
