package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.AdminAnalysisRunDto;
import com.ticket.defender_core.adapter.out.persistence.ListingContextAnalysisRepository;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminActor;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/** Reads only persisted Track 2 Listing Context attempts; it never infers missing Track 1 executions. */
@Service
@RequiredArgsConstructor
public class AdminAnalysisRunQueryService {
    private static final Set<AdminRole> VIEW_ROLES = Set.of(AdminRole.ANALYST, AdminRole.REVIEWER);
    private static final String COVERAGE = "PERSISTED_TRACK2_LISTING_CONTEXT_ANALYSES_ONLY";

    private final AdminAccessService access;
    private final ListingContextAnalysisRepository analyses;

    @Transactional(readOnly = true)
    public AdminAnalysisRunDto.Page list(UUID eventId, int page, int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > 100 || (long) page * pageSize > Integer.MAX_VALUE)
            throw new CaseApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        AdminActor actor = access.requireEventAccess(eventId, VIEW_ROLES);
        var found = analyses.findByTenantIdAndEventId(actor.tenantId(), actor.eventId(),
                PageRequest.of(page, pageSize, Sort.by("createdAt").descending().and(Sort.by("id").descending())));
        return new AdminAnalysisRunDto.Page(found.stream().map(AdminAnalysisRunDto.Item::from).toList(),
                page, pageSize, found.hasNext(), COVERAGE);
    }
}
