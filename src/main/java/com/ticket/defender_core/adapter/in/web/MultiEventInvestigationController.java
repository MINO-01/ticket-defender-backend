package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.application.service.MultiEventInvestigationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** 다공연 조사 후보 API. tenant는 본문에서 받지 않고 event 접근 권한으로 해석한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/multi-event-investigations")
public class MultiEventInvestigationController {
    private final MultiEventInvestigationService service;

    @PostMapping
    public ResponseEntity<MultiEventInvestigationService.Accepted> create(@RequestHeader("Idempotency-Key") String key,
            @RequestBody MultiEventInvestigationService.CreateRequest input) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).header("Cache-Control","no-store").body(service.create(key,input));
    }
    @GetMapping("/events/{eventId}") public ResponseEntity<MultiEventInvestigationService.Page<MultiEventInvestigationService.Run>> list(@PathVariable UUID eventId,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int pageSize){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.list(eventId,page,pageSize));}
    @GetMapping("/events/{eventId}/performances") public ResponseEntity<MultiEventInvestigationService.Page<MultiEventInvestigationService.ScopeView>> available(@PathVariable UUID eventId,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="100")int pageSize){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.available(eventId,page,pageSize));}
    @GetMapping("/events/{eventId}/{runId}") public ResponseEntity<MultiEventInvestigationService.Run> get(@PathVariable UUID eventId,@PathVariable UUID runId){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.get(eventId,runId));}
    @PostMapping("/events/{eventId}/{runId}/retry") public ResponseEntity<MultiEventInvestigationService.Accepted> retry(@PathVariable UUID eventId,@PathVariable UUID runId){return ResponseEntity.status(HttpStatus.ACCEPTED).header("Cache-Control","no-store").body(service.retry(eventId,runId));}
    @GetMapping("/events/{eventId}/{runId}/candidates") public ResponseEntity<MultiEventInvestigationService.Page<MultiEventInvestigationService.Candidate>> candidates(@PathVariable UUID eventId,@PathVariable UUID runId,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int pageSize){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.candidates(eventId,runId,page,pageSize));}
    @GetMapping("/events/{eventId}/{runId}/candidate-families") public ResponseEntity<MultiEventInvestigationService.Page<MultiEventInvestigationService.CandidateFamily>> candidateFamilies(@PathVariable UUID eventId,@PathVariable UUID runId,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int pageSize){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.candidateFamilies(eventId,runId,page,pageSize));}
    @GetMapping("/events/{eventId}/{runId}/candidates/{candidateId}/members") public ResponseEntity<MultiEventInvestigationService.Page<MultiEventInvestigationService.Member>> members(@PathVariable UUID eventId,@PathVariable UUID runId,@PathVariable String candidateId,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="100")int pageSize){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.members(eventId,runId,candidateId,page,pageSize));}
    @GetMapping("/events/{eventId}/{runId}/candidates/{candidateId}/relationships") public ResponseEntity<MultiEventInvestigationService.Page<MultiEventInvestigationService.Relationship>> relationships(@PathVariable UUID eventId,@PathVariable UUID runId,@PathVariable String candidateId,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int pageSize){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.relationships(eventId,runId,candidateId,page,pageSize));}
    @GetMapping("/events/{eventId}/{runId}/sources") public ResponseEntity<List<MultiEventInvestigationService.ScopeView>> sources(@PathVariable UUID eventId,@PathVariable UUID runId){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.scopes(eventId,runId));}
}
