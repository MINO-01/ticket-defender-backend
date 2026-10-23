package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.CaseDto;
import com.ticket.defender_core.application.service.CaseService;
import com.ticket.defender_core.domain.Case;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
@RequiredArgsConstructor
public class CaseController {
    private final CaseService cases;

    @PostMapping
    public ResponseEntity<CaseDto.View> create(@RequestHeader("Idempotency-Key") String key,
            @RequestBody CaseDto.Create input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cases.create(input, key));
    }

    @GetMapping
    public CaseDto.Page list(@RequestParam UUID eventId,
            @RequestParam(required = false) Case.Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return cases.list(eventId, status, page, pageSize);
    }

    @GetMapping("/{caseId}")
    public CaseDto.View detail(@PathVariable UUID caseId) {
        return cases.detail(caseId);
    }

    @GetMapping("/{caseId}/sources")
    public List<CaseDto.Source> sources(@PathVariable UUID caseId) {
        return cases.sources(caseId);
    }

    @GetMapping("/{caseId}/activities")
    public CaseDto.Activities activities(@PathVariable UUID caseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return cases.activities(caseId, page, pageSize);
    }

    @PostMapping("/{caseId}/transitions")
    public CaseDto.View transition(@PathVariable UUID caseId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody CaseDto.Transition input) {
        return cases.transition(caseId, input, key);
    }

    @PutMapping("/{caseId}/assignee")
    public CaseDto.View assign(@PathVariable UUID caseId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody CaseDto.Assign input) {
        return cases.assign(caseId, input, key);
    }
}
