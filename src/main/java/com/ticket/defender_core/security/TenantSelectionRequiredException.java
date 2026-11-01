package com.ticket.defender_core.security;

/**
 * 검증된 관리자 subject가 2개 이상의 활성 고객사에 활성 멤버십을 가질 때 관리자 업무를 차단합니다.
 * 고객사 선택 정책(DEC-02)이 확정되기 전의 임시 정책이며, 고객사나 공연 정보는 담지 않습니다.
 */
public class TenantSelectionRequiredException extends RuntimeException {
    public TenantSelectionRequiredException() {
        super("Tenant selection is required", null, false, false);
    }
}
