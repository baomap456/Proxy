package com.proxy.backend.policy;

import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import com.proxy.common.dto.PolicyRule;

@RestController
@RequestMapping("/api/policies")
public class PolicyController {

    private static final Set<String> TYPES = Set.of("DOMAIN", "KEYWORD", "IP");
    private static final Set<String> ACTIONS = Set.of("BLOCK", "ALLOW");

    private final PolicyRepository repo;

    public PolicyController(PolicyRepository repo) { this.repo = repo; }

    @GetMapping("/active")
    public List<PolicyRule> active() { return repo.findActive(); }

    @GetMapping
    public List<PolicyRule> all() { return repo.findAll(); }

    @GetMapping("/{id}")
    public ResponseEntity<PolicyRule> one(@PathVariable long id) {
        return repo.findById(id).map(ResponseEntity::ok)
                   .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<PolicyRule> create(@RequestBody PolicyRule r) {
        validate(r);
        long id = repo.insert(r);
        return ResponseEntity.status(HttpStatus.CREATED).body(repo.findById(id).orElseThrow());
    }

    @PutMapping("/{id}")
    public ResponseEntity<PolicyRule> update(@PathVariable long id, @RequestBody PolicyRule r) {
        validate(r);
        if (repo.update(id, r) == 0) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(repo.findById(id).orElseThrow());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        return repo.delete(id) == 0
                ? ResponseEntity.notFound().build()
                : ResponseEntity.noContent().build();
    }

    private void validate(PolicyRule r) {
        if (r == null || r.getValue() == null || r.getValue().isBlank()
                || !TYPES.contains(r.getType()) || !ACTIONS.contains(r.getAction())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "type phải là DOMAIN/KEYWORD/IP, action phải là BLOCK/ALLOW, value không được rỗng");
        }
        r.setValue(r.getValue().trim());
    }
}