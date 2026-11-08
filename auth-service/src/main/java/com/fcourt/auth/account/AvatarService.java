package com.fcourt.auth.account;

import com.fcourt.auth.common.ApiException;
import java.io.IOException;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Lets an authenticated user set or clear their own avatar. The file type is read from the content (JPEG, PNG, WebP),
 * the size is capped, and the folder is derived from the token subject, never from client input.
 * Replacing or clearing removes the previous Cloudinary file; avatars from Google sign-in live on another host and
 * are only unlinked. Reviews keep their own snapshot URL, so an old review may show the generic icon after a change.
 */
@Service
class AvatarService {
    private static final Logger log = LoggerFactory.getLogger(AvatarService.class);
    static final long MAX_BYTES = 2L * 1024 * 1024;

    private final AccountRepository accounts;
    private final DocumentStorage storage;

    AvatarService(AccountRepository accounts, DocumentStorage storage) {
        this.accounts = accounts;
        this.storage = storage;
    }

    @Transactional
    void set(long userId, MultipartFile file) {
        requireActive(userId);
        if (file == null || file.isEmpty()) throw ApiException.badRequest("Avatar file is required");
        if (file.getSize() > MAX_BYTES) throw ApiException.badRequest("Avatar must be at most 2 MB");
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException error) {
            throw ApiException.badRequest("Uploaded file could not be read");
        }
        if (!isImage(content)) throw ApiException.badRequest("Avatar accepts only JPEG, PNG or WebP images");
        String previous = accounts.avatarUrl(userId);
        String url = storage.store(content, folder(userId));
        accounts.updateAvatar(userId, url);
        deleteOwned(userId, previous);
    }

    @Transactional
    void clear(long userId) {
        requireActive(userId);
        String previous = accounts.avatarUrl(userId);
        accounts.updateAvatar(userId, null);
        deleteOwned(userId, previous);
    }

    static String folder(long userId) {
        return "avatars/" + userId;
    }

    /** Only files we uploaded for this user are deleted; anything else (Google photo) is merely unlinked. */
    private void deleteOwned(long userId, String url) {
        if (url != null && url.contains("/fcourt/" + folder(userId) + "/")) {
            storage.delete(url);
        } else if (url != null) {
            log.debug("Avatar of user {} is not hosted by us, unlinked only", userId);
        }
    }

    private void requireActive(long userId) {
        var account = accounts.findById(userId).orElseThrow(() -> ApiException.notFound("Account not found"));
        AccountPolicy.requireActive(account);
    }

    static boolean isImage(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return true;
        if (b.length >= 8 && Arrays.equals(Arrays.copyOf(b, 8),
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})) return true;
        return b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }
}
