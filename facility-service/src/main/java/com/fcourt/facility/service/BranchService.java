package com.fcourt.facility.service;

import com.fcourt.facility.api.BranchRequest;
import com.fcourt.facility.api.BranchResponse;
import com.fcourt.facility.events.BranchEventWriter;
import com.fcourt.facility.integration.SubscriptionLimitClient;
import com.fcourt.facility.persistence.BranchStore;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Branch CRUD for a court owner (SCRUM-14). Every query is scoped by the caller's admin id. */
@Service
public class BranchService {
    private final BranchStore branches;
    private final BranchEventWriter events;
    private final SubscriptionLimitClient subscriptions;
    private final TransactionTemplate tx;

    public BranchService(BranchStore branches, BranchEventWriter events, SubscriptionLimitClient subscriptions,
                         TransactionTemplate tx) {
        this.branches = branches;
        this.events = events;
        this.subscriptions = subscriptions;
        this.tx = tx;
    }

    public List<BranchResponse> list(long adminId) {
        return branches.listOwned(adminId);
    }

    public List<BranchResponse> listAll() {
        return branches.listAll();
    }

    public BranchResponse get(long adminId, long branchId) {
        return branches.findOwned(branchId, adminId).orElseThrow(BranchService::notFound);
    }

    public BranchResponse create(long adminId, String bearer, BranchRequest request) {
        var limits = subscriptions.limits(adminId, bearer); // remote call kept outside the DB transaction
        long id = tx.execute(status -> {
            branches.lockOwner(adminId);
            requireUniqueName(request.name(), null);
            if (branches.countOwned(adminId) >= limits.maxBranches()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Branch limit of your subscription plan reached (" + limits.maxBranches() + ")");
            }
            long created = insertOrDuplicate(adminId, request);
            events.branchChanged(created);
            return created;
        });
        return get(adminId, id);
    }

    public BranchResponse update(long adminId, long branchId, BranchRequest request) {
        tx.executeWithoutResult(status -> {
            if (!branches.lockOwned(branchId, adminId)) throw notFound();
            requireUniqueName(request.name(), branchId);
            requireSlotDurationChangeAllowed(branchId, request.slotDurationMinutes());
            try {
                branches.update(branchId, request);
            } catch (DuplicateKeyException race) {
                throw duplicateName(race);
            }
            events.branchChanged(branchId);
        });
        return get(adminId, branchId);
    }

    public void delete(long adminId, long branchId) {
        tx.executeWithoutResult(status -> {
            if (!branches.lockOwned(branchId, adminId)) throw notFound();
            branches.softDelete(branchId);
            events.branchChanged(branchId);
        });
    }

    public BranchResponse.Public publicBranch(long branchId) {
        return branches.find(branchId).orElseThrow(BranchService::notFound).toPublic();
    }

    public BranchResponse.Status staffBranch(long branchId) {
        var branch = branches.find(branchId).orElseThrow(BranchService::notFound);
        return new BranchResponse.Status(branch.id(), branch.status());
    }

    private void requireUniqueName(String name, Long exceptBranchId) {
    if (branches.nameTaken(name, exceptBranchId)) throw duplicateName(null);
}

private long insertOrDuplicate(long adminId, BranchRequest request) {
    try {
        return branches.insert(adminId, request);
    } catch (DuplicateKeyException race) {
        throw duplicateName(race);
    }
}

private void requireSlotDurationChangeAllowed(long branchId, Integer requested) {
    if (requested != null && requested != branches.find(branchId).orElseThrow(BranchService::notFound).slotDurationMinutes()
            && branches.hasLiveTemplates(branchId)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Cannot change slot duration after time slots have been created");
    }
}

private static ResponseStatusException duplicateName(Throwable cause) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch name already exists", cause);
}

static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found");
    }
}
