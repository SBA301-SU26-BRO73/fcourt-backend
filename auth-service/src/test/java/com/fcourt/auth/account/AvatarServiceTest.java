package com.fcourt.auth.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fcourt.auth.account.AccountRepository.AccountRecord;
import com.fcourt.auth.common.ApiException;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

class AvatarServiceTest {
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    static final String OWN = "avatars/7/old.png";
    static final String NEW = "avatars/7/new.png";
    AccountRepository accounts;
    DocumentStorage storage;
    AvatarService service;

    static AccountRecord account(String status) {
        return new AccountRecord(7, "a@b.c", "hash", "Full Name", "0909000001", "CUSTOMER", status, OffsetDateTime.now());
    }

    static MockMultipartFile file(byte[] content, String declaredType) {
        return new MockMultipartFile("file", "a.png", declaredType, content);
    }

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        storage = mock(DocumentStorage.class);
        service = new AvatarService(accounts, storage);
        when(accounts.findById(7)).thenReturn(Optional.of(account("ACTIVE")));
    }

    static void assertBadRequest(Throwable error) {
        assertThat(error).isInstanceOfSatisfying(ApiException.class, api -> assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void setStoresUnderTheUsersOwnFolderSavesTheUrlAndDeletesOnlyTheOldOwnedFile() {
        when(accounts.avatarUrl(7)).thenReturn(OWN);
        when(storage.storePublic(any(), eq("avatars/7"))).thenReturn(NEW);
        service.set(7, file(PNG, "text/plain"));
        verify(accounts).updateAvatar(7, NEW);
        verify(storage).delete(OWN);
    }

    @Test
    void googleOrForeignPreviousAvatarIsUnlinkedButNeverDeleted() {
        for (String previous : new String[] {"https://lh3.googleusercontent.com/a.jpg", "https://res.cloudinary.com/demo/image/upload/v1/fcourt/avatars/7/legacy.png", "avatars/8/x.png", null}) {
            when(accounts.avatarUrl(7)).thenReturn(previous);
            when(storage.storePublic(any(), anyString())).thenReturn(NEW);
            service.set(7, file(PNG, "image/png"));
        }
        verify(storage, never()).delete(anyString());
    }

    @Test
    void contentIsCheckedByMagicBytesNotByDeclaredType() {
        for (byte[] bad : new byte[][] {"<svg/>".getBytes(), "GIF89a....".getBytes(), "%PDF-1.4....".getBytes(), "MZ\u0090\u0000".getBytes()}) {
            assertThatThrownBy(() -> service.set(7, file(bad, "image/png"))).satisfies(AvatarServiceTest::assertBadRequest);
        }
        verify(storage, never()).storePublic(any(), anyString());
        verify(accounts, never()).updateAvatar(anyLong(), any());
    }

    @Test
    void emptyMissingAndOversizedFilesAreRejectedBeforeStorage() {
        byte[] big = new byte[(int) AvatarService.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertThatThrownBy(() -> service.set(7, file(new byte[0], "image/png"))).satisfies(AvatarServiceTest::assertBadRequest);
        assertThatThrownBy(() -> service.set(7, null)).satisfies(AvatarServiceTest::assertBadRequest);
        assertThatThrownBy(() -> service.set(7, file(big, "image/png"))).satisfies(AvatarServiceTest::assertBadRequest);
        verify(storage, never()).storePublic(any(), anyString());
    }

    @Test
    void lockedOrMissingAccountsCannotChangeAvatar() {
        when(accounts.findById(7)).thenReturn(Optional.of(account("LOCKED")));
        assertThatThrownBy(() -> service.set(7, file(PNG, "image/png"))).isInstanceOf(ApiException.class);
        when(accounts.findById(7)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.clear(7)).isInstanceOf(ApiException.class);
        verify(storage, never()).storePublic(any(), anyString());
        verify(accounts, never()).updateAvatar(anyLong(), any());
    }

    @Test
    void clearRemovesTheLinkAndOwnedFileAndIsIdempotent() {
        when(accounts.avatarUrl(7)).thenReturn(OWN);
        service.clear(7);
        verify(accounts).updateAvatar(7, null);
        verify(storage).delete(OWN);
        when(accounts.avatarUrl(7)).thenReturn(null);
        service.clear(7);
        verify(storage).delete(anyString());
    }
}
