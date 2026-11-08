package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.AdminOperationSettingsDto;
import com.ticket.defender_core.adapter.out.api.ListingContextClient;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminOperationSettingsService {
    private static final Set<AdminRole> VIEW_ROLES = Set.of(AdminRole.TENANT_ADMIN);

    private final AdminAccessService access;
    private final ListingContextClient listingContextClient;
    private final OfficialPdfOperationGate officialPdfOperations;

    @Value("${ticket-defender.fan-report.enabled:false}")
    private boolean fanReportIntakeEnabled;

    @Transactional(readOnly = true)
    public AdminOperationSettingsDto get(UUID eventId) {
        access.requireEventAccess(eventId, VIEW_ROLES);
        return new AdminOperationSettingsDto(fanReportIntakeEnabled, listingContextClient.configured(),
                "STORED_DEVICE_AND_IP_SIGNALS_ONLY",
                officialPdfOperations.enabledForSyntheticIsolation() ? "SYNTHETIC_ISOLATION_ONLY" : "BLOCKED",
                "NOT_IMPLEMENTED_POLICY_PENDING");
    }
}
