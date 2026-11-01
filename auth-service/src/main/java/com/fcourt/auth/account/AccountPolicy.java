package com.fcourt.auth.account;

import com.fcourt.auth.account.AccountRepository.AccountRecord;
import com.fcourt.auth.common.ApiException;
import com.fcourt.auth.user.UserStatus;

/** Which account states may hold a session. Used by login, refresh and profile. */
final class AccountPolicy {
    private AccountPolicy() {
    }

    static void requireActive(AccountRecord account) {
        UserStatus status = UserStatus.valueOf(account.status());
        switch (status) {
            case ACTIVE -> {
            }
            case PENDING_APPROVAL -> throw ApiException.forbidden("Account is awaiting approval");
            case REJECTED -> throw ApiException.forbidden("Account registration was rejected");
            case LOCKED -> throw ApiException.forbidden("Account is locked");
            case INACTIVE -> throw ApiException.forbidden("Account is inactive");
        }
    }
}
