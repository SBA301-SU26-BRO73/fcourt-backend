package com.fcourt.facility.service;

import com.fcourt.facility.api.CourtResponse;
import com.fcourt.facility.events.BranchEventWriter;
import com.fcourt.facility.persistence.BranchStore;
import com.fcourt.facility.persistence.CourtStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Court photo: one image per court under {@code branches/{branchId}/courts/{courtId}/{uuid}.{ext}}, so it is
 * public under the existing {@code branches/*} prefix. The file is validated by its bytes, uploaded outside any DB
 * transaction, then the court row is updated under the branch lock and the branch snapshot event is emitted like
 * any other court change. The replaced object is removed only after the commit, and only when it lives in this
 * court's own folder (URLs pasted by hand or the branch gallery are never touched).
 */
@Service
public class CourtImageService {
    private static final Logger log = LoggerFactory.getLogger(CourtImageService.class);

    private final CourtService courtService;
    private final CourtStore courts;
    private final BranchStore branches;
    private final ImageStorage storage;
    private final ImageUploadValidator validator;
    private final TransactionTemplate tx;
    private final BranchEventWriter events;

    public CourtImageService(CourtService courtService, CourtStore courts, BranchStore branches, ImageStorage storage,
                             ImageUploadValidator validator, TransactionTemplate tx, BranchEventWriter events) {
        this.courtService = courtService;
        this.courts = courts;
        this.branches = branches;
        this.storage = storage;
        this.validator = validator;
        this.tx = tx;
        this.events = events;
    }

    public CourtResponse replace(long adminId, long courtId, MultipartFile file) {
        long branchId = courtService.owned(adminId, courtId).court().branchId();
        var upload = validator.validate(file);
        var stored = storage.store(folder(branchId, courtId), upload);
        String previous;
        try {
            previous = tx.execute(status -> change(adminId, courtId, branchId, stored.url()));
        } catch (RuntimeException failure) {
            removeQuietly(stored.publicId(), failure);
            throw failure;
        }
        removeOwnObject(previous, branchId, courtId);
        return courtService.get(adminId, courtId);
    }

    public CourtResponse remove(long adminId, long courtId) {
        long branchId = courtService.owned(adminId, courtId).court().branchId();
        String previous = tx.execute(status -> change(adminId, courtId, branchId, null));
        removeOwnObject(previous, branchId, courtId);
        return courtService.get(adminId, courtId);
    }

    /** Runs inside the transaction: returns the image URL that was replaced. */
    private String change(long adminId, long courtId, long branchId, String newUrl) {
        if (!branches.lockOwned(branchId, adminId)) throw CourtService.notFound();
        String previous = courtService.owned(adminId, courtId).court().imageUrl();
        courts.updateImage(courtId, newUrl);
        events.branchChanged(branchId);
        return previous;
    }

    static String folder(long branchId, long courtId) {
        return "branches/" + branchId + "/courts/" + courtId;
    }

    /** The storage key of an image in this court's folder, or null for anything else. */
    static String ownKey(String url, long branchId, long courtId) {
        if (url == null) return null;
        String marker = "/" + folder(branchId, courtId) + "/";
        int at = url.indexOf(marker);
        if (at < 0) return null;
        String key = url.substring(at + 1);
        return key.contains("..") || key.contains("?") || key.contains("#") ? null : key;
    }

    private void removeOwnObject(String previousUrl, long branchId, long courtId) {
        String key = ownKey(previousUrl, branchId, courtId);
        if (key == null) return;
        try {
            storage.delete(key);
        } catch (RuntimeException failure) {
            log.warn("Replaced court image {} could not be removed: {}", key, failure.getMessage());
        }
    }

    private void removeQuietly(String publicId, RuntimeException original) {
        try {
            storage.delete(publicId);
        } catch (RuntimeException cleanup) {
            log.error("Orphaned court image {} after failed update ({}): {}", publicId, original.getMessage(),
                    cleanup.getMessage());
        }
    }
}
