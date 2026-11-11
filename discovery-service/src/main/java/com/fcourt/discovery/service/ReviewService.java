package com.fcourt.discovery.service;

import com.fcourt.discovery.component.*;
import com.fcourt.discovery.dto.request.*;
import com.fcourt.discovery.dto.response.ReviewPage;
import com.fcourt.discovery.model.Review;
import com.fcourt.discovery.repository.ReviewRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewService {
    private final ReviewRepository repository;
    private final ReviewBookingClient booking;
    private final ReviewBranchAuthority authority;
    private final TransactionTemplate tx;
    private final ReviewerProfileClient reviewers;
    private final ReviewImagePolicy images;
    public ReviewService(ReviewRepository repository, ReviewBookingClient booking, ReviewBranchAuthority authority, TransactionTemplate tx,
            ReviewerProfileClient reviewers, ReviewImagePolicy images) {
        this.repository = repository; this.booking = booking; this.authority = authority; this.tx = tx;
        this.reviewers = reviewers; this.images = images;
    }

    public Review create(CreateReviewRequest request, Jwt jwt) {
        long user = ReviewIdentity.require(jwt, "CUSTOMER");
        // Only URLs issued by our own uploader (caller's folder) are accepted; checked before any remote call.
        if (!images.allowsAll(request.imageUrls(), user))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ảnh đánh giá phải được tải lên qua hệ thống");
        var order = booking.completed(request.bookingId(), jwt);
        var reviewer = reviewers.snapshot(jwt);
        try {
            return tx.execute(ignored -> {
                var review = repository.insert(order.branchId(), order.courtId(), user, reviewer, request);
                repository.updateRating(order.branchId(), request.rating());
                return review;
            });
        } catch (DuplicateKeyException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Đơn đặt sân này đã được đánh giá");
        }
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public ReviewPage list(long branchId, int page, int size) {
        if (branchId <= 0 || page < 0 || page > 100000 || size < 1 || size > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tham số đánh giá không hợp lệ");
        if (!repository.publicBranch(branchId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy chi nhánh");
        return repository.page(branchId, page, size);
    }

    public Review reply(long id, ReplyReviewRequest request, Jwt jwt) {
        if (id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID đánh giá không hợp lệ");
        long actor = ReviewIdentity.require(jwt, "ADMIN");
        var branches = authority.owned(jwt);
        return tx.execute(ignored -> repository.reply(id, branches, actor, request.reply())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đánh giá trong chi nhánh của bạn")));
    }
}
