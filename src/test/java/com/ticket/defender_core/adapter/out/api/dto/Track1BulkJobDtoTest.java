package com.ticket.defender_core.adapter.out.api.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Track1BulkJobDtoTest {
    @Test
    void serializesTheData36ManifestFieldNamesWithoutAnInputArray() throws Exception {
        UUID event=UUID.randomUUID(), performance=UUID.randomUUID();
        var scope=new Track1BulkJobDto.Scope(event,"event-ref",performance,"POLICY_NOT_CONFIRMED",null,"INCOMPLETE","source-v1",OffsetDateTime.parse("2026-11-18T00:00:00Z"),"NOT_CONFIRMED","DEDUPLICATED","not confirmed");
        var manifest=new Track1BulkJobDto.Manifest("artifact-1","artifact-1","version-1","a".repeat(64),2,"track1-limit-ndjson-v1","tenant-1","event-ref","ledger-1","3","2","source-v1",OffsetDateTime.parse("2026-11-18T00:00:00Z"),"TRACK1_CONNECTION","key-v1","policy-bundle-1",List.of(scope));
        String body=JsonMapper.builder().build().writeValueAsString(new Track1BulkJobDto.Create(manifest));
        assertThat(body).contains("\"artifactId\":\"artifact-1\"","\"objectVersion\":\"version-1\"","\"performanceScopes\"")
                .doesNotContain("tickets","bucket","secret");
    }
}
