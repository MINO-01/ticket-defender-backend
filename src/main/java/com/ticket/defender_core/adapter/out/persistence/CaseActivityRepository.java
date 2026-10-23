package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.CaseActivity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CaseActivityRepository extends JpaRepository<CaseActivity, UUID> {
    Page<CaseActivity> findByCaseId(UUID caseId, Pageable pageable);
}
