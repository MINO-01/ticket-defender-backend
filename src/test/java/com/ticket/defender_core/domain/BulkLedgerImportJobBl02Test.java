package com.ticket.defender_core.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkLedgerImportJobBl02Test {
    @Test
    void requiresUploadThenFencedStagingBeforeReady() {
        LocalDateTime now = LocalDateTime.now();
        BulkLedgerImportJob job = job(now);
        job.beginUpload("a".repeat(64), now.plusMinutes(1), now);
        job.completeUpload("version-1", now.plusSeconds(1));
        job.startValidation("worker-a", now.plusMinutes(6), now.plusSeconds(2));
        long fence = job.getFenceToken();
        job.beginStaging(fence, now.plusSeconds(3));
        assertThatThrownBy(() -> job.readyToPublish(fence - 1, 1, 1, 0, now.plusSeconds(4))).isInstanceOf(IllegalStateException.class);
        job.readyToPublish(fence, 1, 1, 0, now.plusSeconds(4));
        assertThat(job.getStatus()).isEqualTo(BulkLedgerImportJob.Status.READY_TO_PUBLISH);
    }

    @Test
    void expiredLeaseCanBeReclaimedWithNewFence() {
        LocalDateTime now = LocalDateTime.now(); BulkLedgerImportJob job = job(now);
        job.beginUpload("a".repeat(64), now.plusMinutes(1), now); job.completeUpload("version-1", now.plusSeconds(1));
        job.startValidation("old", now.plusSeconds(2), now.plusSeconds(1)); job.beginStaging(job.getFenceToken(), now.plusSeconds(1));
        long oldFence = job.getFenceToken(); job.startValidation("new", now.plusMinutes(1), now.plusSeconds(3));
        assertThat(job.getFenceToken()).isGreaterThan(oldFence);
    }

    private static BulkLedgerImportJob job(LocalDateTime now) {
        return new BulkLedgerImportJob(UUID.randomUUID(), UUID.randomUUID(), "b".repeat(64), "c".repeat(64), "v1", "SYNTHETIC",
                "batch", BulkLedgerImportJob.Mode.FULL_SNAPSHOT, BulkLedgerImportJob.Format.CSV, "a".repeat(64), 1, 1, true, now);
    }
}
