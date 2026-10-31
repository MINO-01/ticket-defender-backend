package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.OfficialPdfIssuanceAttempt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OfficialPdfIssuanceAttemptRepository extends JpaRepository<OfficialPdfIssuanceAttempt, UUID> {
    List<OfficialPdfIssuanceAttempt> findByIssuanceIdOrderByAttemptNumberAsc(UUID issuanceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select attempt from OfficialPdfIssuanceAttempt attempt where attempt.issuanceId = :issuanceId "
            + "and attempt.status = :status order by attempt.attemptNumber desc")
    List<OfficialPdfIssuanceAttempt> findByIssuanceIdAndStatusForUpdate(
            UUID issuanceId, OfficialPdfIssuanceAttempt.Status status);
}
