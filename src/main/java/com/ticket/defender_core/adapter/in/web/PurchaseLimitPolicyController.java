package com.ticket.defender_core.adapter.in.web;
import com.ticket.defender_core.adapter.in.web.dto.PurchaseLimitPolicyDto; import com.ticket.defender_core.application.service.PurchaseLimitPolicyService; import lombok.RequiredArgsConstructor; import org.springframework.http.*; import org.springframework.web.bind.annotation.*; import java.util.*;
@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/admin/events/{eventId}/purchase-limit-policies")
public class PurchaseLimitPolicyController {
 private final PurchaseLimitPolicyService service;
 @PostMapping public ResponseEntity<PurchaseLimitPolicyDto.View> create(@PathVariable UUID eventId,@RequestBody PurchaseLimitPolicyDto.Create input){return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control","no-store").body(service.create(eventId,input));}
 @GetMapping public ResponseEntity<List<PurchaseLimitPolicyDto.View>> list(@PathVariable UUID eventId){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.list(eventId));}
 @PatchMapping("/{policyId}") public ResponseEntity<PurchaseLimitPolicyDto.View> revise(@PathVariable UUID eventId,@PathVariable UUID policyId,@RequestBody PurchaseLimitPolicyDto.Revise input){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.revise(eventId,policyId,input));}
 @PostMapping("/{policyId}/withdrawals") public ResponseEntity<PurchaseLimitPolicyDto.View> withdraw(@PathVariable UUID eventId,@PathVariable UUID policyId,@RequestBody PurchaseLimitPolicyDto.Withdraw input){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.withdraw(eventId,policyId,input));}
}
