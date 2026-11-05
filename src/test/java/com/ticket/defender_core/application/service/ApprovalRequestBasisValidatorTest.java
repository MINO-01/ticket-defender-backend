package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.CaseSourceReferenceRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidencePreservationPolicyRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidencePreservationRecordRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceVerificationRecordRepository;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseSourceReference;
import com.ticket.defender_core.domain.EvidenceSnapshot;
import com.ticket.defender_core.domain.ReportVersion;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovalRequestBasisValidatorTest {
    @Test
    void candidateRunRemainsAnInvestigativeSourceAndCannotMakeAnApprovalBasisEligible() {
        CaseSourceReferenceRepository sources = mock(CaseSourceReferenceRepository.class);
        CaseSnapshotManifest manifests = mock(CaseSnapshotManifest.class);
        ApprovalRequestBasisValidator validator = new ApprovalRequestBasisValidator(sources,
                mock(EvidenceAssetRepository.class), mock(EvidenceVerificationRecordRepository.class),
                mock(EvidencePreservationRecordRepository.class), mock(EvidencePreservationPolicyRepository.class), manifests);
        Case target = mock(Case.class);
        UUID caseId = UUID.randomUUID();
        when(target.getId()).thenReturn(caseId);
        CaseSourceReference candidate = mock(CaseSourceReference.class);
        when(candidate.getSourceType()).thenReturn(CaseSourceReference.Type.LISTING_CANDIDATE_MATCH);
        when(sources.findByCaseIdOrderByLinkedAtAscIdAsc(caseId)).thenReturn(List.of(candidate));
        EvidenceSnapshot snapshot = mock(EvidenceSnapshot.class);
        ReportVersion report = mock(ReportVersion.class);
        LocalDateTime now = LocalDateTime.parse("2026-11-05T12:00:00");

        assertThatThrownBy(() -> validator.validate(target, snapshot, report, now))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("APPROVAL_BASIS_BLOCKED"));
        verify(manifests).recheckStoredDatabase(target, List.of(candidate), snapshot, now);
    }
}
