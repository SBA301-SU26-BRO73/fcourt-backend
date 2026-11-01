package com.fcourt.facility.service;

import com.fcourt.facility.api.CourtRequest;
import com.fcourt.facility.api.CourtResponse;
import com.fcourt.facility.events.BranchEventWriter;
import com.fcourt.facility.integration.SubscriptionLimitClient;
import com.fcourt.facility.persistence.BranchStore;
import com.fcourt.facility.persistence.CourtStore;
import com.fcourt.facility.persistence.CourtStore.OwnedCourt;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Court CRUD inside an owned branch (SCRUM-16). */
@Service
public class CourtService {
    private final BranchStore branches;
    private final CourtStore courts;
    private final BranchEventWriter events;
    private final SubscriptionLimitClient subscriptions;
    private final TransactionTemplate tx;

    public CourtService(BranchStore branches, CourtStore courts, BranchEventWriter events,
                        SubscriptionLimitClient subscriptions, TransactionTemplate tx) {
        this.branches = branches;
        this.courts = courts;
        this.events = events;
        this.subscriptions = subscriptions;
        this.tx = tx;
    }

    public List<CourtResponse> list(long adminId, long branchId) {
        branches.findOwned(branchId, adminId).orElseThrow(BranchService::notFound);
        return courts.listByBranch(branchId);
    }

    public CourtResponse get(long adminId, long courtId) {
        return owned(adminId, courtId).court();
    }

    public CourtResponse create(long adminId, String bearer, long branchId, CourtRequest request) {
        branches.findOwned(branchId, adminId).orElseThrow(BranchService::notFound);
        var limits = subscriptions.limits(adminId, bearer);
        long id = tx.execute(status -> {
            branches.lockOwner(adminId);
            if (!branches.lockOwned(branchId, adminId)) throw BranchService.notFound();
            if (courts.countOwned(adminId) >= limits.maxCourts()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Court limit of your subscription plan reached (" + limits.maxCourts() + ")");
            }
            requireUniqueName(branchId, request.name(), null);
            long created = insertOrDuplicate(branchId, request);
            events.branchChanged(branchId);
            return created;
        });
        return get(adminId, id);
    }

    public CourtResponse update(long adminId, long courtId, CourtRequest request) {
        long branchId = owned(adminId, courtId).court().branchId();
        tx.executeWithoutResult(status -> {
            if (!branches.lockOwned(branchId, adminId)) throw notFound();
            owned(adminId, courtId);
            requireUniqueName(branchId, request.name(), courtId);
            try {
                courts.update(courtId, request);
            } catch (DuplicateKeyException race) {
                throw duplicateName(race);
            }
            events.branchChanged(branchId);
        });
        return get(adminId, courtId);
    }

    public CourtResponse changeStatus(long adminId, long courtId, String status) {
        long branchId = owned(adminId, courtId).court().branchId();
        tx.executeWithoutResult(ignored -> {
            if (!branches.lockOwned(branchId, adminId)) throw notFound();
            owned(adminId, courtId);
            courts.updateStatus(courtId, status);
            events.branchChanged(branchId);
        });
        return get(adminId, courtId);
    }

    public void delete(long adminId, long courtId) {
        long branchId = owned(adminId, courtId).court().branchId();
        tx.executeWithoutResult(status -> {
            if (!branches.lockOwned(branchId, adminId)) throw notFound();
            owned(adminId, courtId);
            courts.softDelete(courtId);
            events.branchChanged(branchId);
        });
    }

    public CourtResponse.StaffView staffCourt(long courtId) {
        return courts.find(courtId).orElseThrow(CourtService::notFound).court().toStaffView();
    }

    OwnedCourt owned(long adminId, long courtId) {
        return courts.find(courtId).filter(court -> court.adminId() == adminId).orElseThrow(CourtService::notFound);
    }

    private void requireUniqueName(long branchId, String name, Long exceptCourtId) {
        if (courts.nameTaken(branchId, name, exceptCourtId)) throw duplicateName(null);
    }

    private long insertOrDuplicate(long branchId, CourtRequest request) {
        try {
            return courts.insert(branchId, request);
        } catch (DuplicateKeyException race) {
            throw duplicateName(race);
        }
    }

    /** Jira SCRUM-16 AC: a duplicate name inside the branch is a 400 validation error. */
    private static ResponseStatusException duplicateName(Throwable cause) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Court name already exists in this branch", cause);
    }

    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Court not found");
    }
}
