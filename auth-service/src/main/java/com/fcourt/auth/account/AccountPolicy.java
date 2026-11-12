package com.fcourt.auth.account;

import com.fcourt.auth.account.AccountRepository.AccountRecord;
import com.fcourt.auth.common.ApiException;
import com.fcourt.auth.user.UserStatus;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Which account states may hold a session. Used by login, refresh and profile. */
final class AccountPolicy {
    private AccountPolicy() {
    }

    static void requireActive(AccountRecord account) {
        requireActive(account, null);
    }

    /** Login variant: a rejected owner also learns why, in the reason field of the error body. */
    static void requireActive(AccountRecord account, AccountRepository accounts) {
        UserStatus status = UserStatus.valueOf(account.status());
        switch (status) {
            case ACTIVE -> {
            }
            case PENDING_APPROVAL -> throw ApiException.forbidden("Account is awaiting approval");
            case REJECTED -> {
                String reason = accounts == null ? null : accounts.rejectionReason(account.id()).orElse(null);
                throw reason == null ? ApiException.forbidden("Account registration was rejected")
                        : new ApiException(HttpStatus.FORBIDDEN, "Account registration was rejected",
                                Map.of("reason", reason));
            }
            case LOCKED -> throw ApiException.forbidden("Account is locked");
            case INACTIVE -> throw ApiException.forbidden("Account is inactive");
        }
    }
}
