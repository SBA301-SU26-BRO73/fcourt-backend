package com.fcourt.subscription.courttype;

import com.fcourt.subscription.common.ApiException;
import com.fcourt.subscription.common.PageResponse;
import com.fcourt.subscription.common.PageResponse.Pagination;
import com.fcourt.subscription.courttype.CourtTypeDtos.CourtTypeRequest;
import com.fcourt.subscription.courttype.CourtTypeDtos.CourtTypeResponse;
import java.time.Clock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Court type catalogue (football, badminton...). Names are unique among types that are not deleted;
 * deleting is a soft delete so the name can be reused later.
 */
@Service
public class CourtTypeService {
    private static final String NAME_TAKEN = "Court type name already exists";

    private final CourtTypeRepository courtTypes;
    private final Clock clock;

    CourtTypeService(CourtTypeRepository courtTypes, Clock clock) {
        this.courtTypes = courtTypes;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<CourtTypeResponse> list(Boolean active, Pagination pagination) {
        return PageResponse.of(courtTypes.findPage(active, pagination.size(), pagination.offset()),
                pagination, courtTypes.count(active));
    }

    @Transactional(readOnly = true)
    public CourtTypeResponse get(long id) {
        return courtTypes.find(id).orElseThrow(CourtTypeService::notFound);
    }

    @Transactional
    public CourtTypeResponse create(CourtTypeRequest request) {
        String name = request.name().strip();
        if (courtTypes.nameInUse(name, 0)) {
            throw ApiException.conflict(NAME_TAKEN);
        }
        long id;
        try {
            id = courtTypes.insert(name, blankToNull(request.nameEn()), blankToNull(request.description()),
                    blankToNull(request.icon()), blankToNull(request.color()), isActive(request), clock.instant());
        } catch (DuplicateKeyException race) {
            throw ApiException.conflict(NAME_TAKEN);
        }
        return get(id);
    }

    @Transactional
    public CourtTypeResponse update(long id, CourtTypeRequest request) {
        get(id);
        String name = request.name().strip();
        if (courtTypes.nameInUse(name, id)) {
            throw ApiException.conflict(NAME_TAKEN);
        }
        try {
            courtTypes.update(id, name, blankToNull(request.nameEn()), blankToNull(request.description()),
                    blankToNull(request.icon()), blankToNull(request.color()), isActive(request), clock.instant());
        } catch (DuplicateKeyException race) {
            throw ApiException.conflict(NAME_TAKEN);
        }
        return get(id);
    }

    @Transactional
    public void delete(long id) {
        get(id);
        courtTypes.softDelete(id, clock.instant());
    }

    private static boolean isActive(CourtTypeRequest request) {
        return request.active() == null || request.active();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static ApiException notFound() {
        return ApiException.notFound("Court type not found");
    }
}
