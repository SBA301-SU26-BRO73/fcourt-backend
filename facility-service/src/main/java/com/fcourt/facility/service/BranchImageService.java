package com.fcourt.facility.service;

import com.fcourt.facility.api.BranchImageResponse;
import com.fcourt.facility.persistence.BranchImageStore;
import com.fcourt.facility.persistence.BranchStore;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Branch photo gallery (SCRUM-47). Files are validated first, uploaded outside any DB transaction
 * (remote call), then recorded under the branch row lock; uploads whose DB insert fails are removed again.
 */
@Service
public class BranchImageService {
    private static final Logger log = LoggerFactory.getLogger(BranchImageService.class);

    private final BranchStore branches;
    private final BranchImageStore images;
    private final ImageStorage storage;
    private final ImageUploadValidator validator;
    private final TransactionTemplate tx;
    private final int maxPerBranch;

    public BranchImageService(BranchStore branches, BranchImageStore images, ImageStorage storage,
                              ImageUploadValidator validator, TransactionTemplate tx,
                              @Value("${facility.images.max-per-branch:10}") int maxPerBranch) {
        this.branches = branches;
        this.images = images;
        this.storage = storage;
        this.validator = validator;
        this.tx = tx;
        this.maxPerBranch = maxPerBranch;
    }

    public List<BranchImageResponse> publicList(long branchId) {
        branches.find(branchId).orElseThrow(BranchService::notFound);
        return images.list(branchId);
    }

    public List<BranchImageResponse> list(long adminId, long branchId) {
        branches.findOwned(branchId, adminId).orElseThrow(BranchService::notFound);
        return images.list(branchId);
    }

    public List<BranchImageResponse> upload(long adminId, long branchId, List<MultipartFile> files) {
        branches.findOwned(branchId, adminId).orElseThrow(BranchService::notFound);
        var uploads = validator.validateAll(files);
        requireCapacity(branchId, uploads.size());

        var stored = new ArrayList<ImageStorage.Stored>();
        try {
            for (var upload : uploads) stored.add(storage.store("fcourt/branches/" + branchId, upload));
            return tx.execute(status -> {
                if (!branches.lockOwned(branchId, adminId)) throw BranchService.notFound();
                requireCapacity(branchId, stored.size());
                return stored.stream().map(item -> images.insert(branchId, item.url(), item.publicId())).toList();
            });
        } catch (RuntimeException failure) {
            for (var item : stored) removeQuietly(item.publicId(), failure);
            throw failure;
        }
    }

    public void delete(long adminId, long imageId) {
        var found = images.find(imageId).orElseThrow(BranchImageService::notFound);
        branches.findOwned(found.image().branchId(), adminId).orElseThrow(BranchImageService::notFound);
        // Remote first: if the host fails the row stays, so the admin can retry and no orphan file is left behind.
        storage.delete(found.publicId());
        images.delete(imageId);
    }

    private void requireCapacity(long branchId, int adding) {
        if (images.count(branchId) + adding > maxPerBranch) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A branch can have at most " + maxPerBranch + " images");
        }
    }

    /** Cleanup after a failed upload; the original failure is rethrown and this one is logged, not hidden. */
    private void removeQuietly(String publicId, RuntimeException original) {
        try {
            storage.delete(publicId);
        } catch (RuntimeException cleanup) {
            log.error("Orphaned remote image {} after failed upload ({}): {}", publicId, original.getMessage(),
                    cleanup.getMessage());
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found");
    }
}
