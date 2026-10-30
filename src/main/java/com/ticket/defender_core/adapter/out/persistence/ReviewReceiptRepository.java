package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.ReviewReceipt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewReceiptRepository extends JpaRepository<ReviewReceipt, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select receipt from ReviewReceipt receipt where receipt.tokenSha256 = :tokenSha256")
    Optional<ReviewReceipt> findByTokenSha256ForUpdate(String tokenSha256);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select receipt from ReviewReceipt receipt where receipt.approvalRequestId = :approvalRequestId "
            + "and receipt.approverMembershipId = :approverMembershipId "
            + "and receipt.status = :status")
    List<ReviewReceipt> findByApprovalRequestIdAndApproverMembershipIdAndStatusForUpdate(
            UUID approvalRequestId, UUID approverMembershipId, ReviewReceipt.Status status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select receipt from ReviewReceipt receipt where receipt.approvalRequestId = :approvalRequestId "
            + "and receipt.status = :status")
    List<ReviewReceipt> findByApprovalRequestIdAndStatusForUpdate(
            UUID approvalRequestId, ReviewReceipt.Status status);
}
