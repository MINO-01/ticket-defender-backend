package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.MacroAuditEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** 매크로 증거 내역을 조회하는 리포지토리입니다. */
public interface MacroAuditEvidenceRepository extends JpaRepository<MacroAuditEvidence, Long> {

    /** 키 목록에 해당하는 기존 증거를 조회합니다. */
    List<MacroAuditEvidence> findAllByEvidenceKeyIn(Collection<String> evidenceKeys);
}
