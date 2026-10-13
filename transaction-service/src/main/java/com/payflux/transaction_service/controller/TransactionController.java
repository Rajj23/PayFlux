package com.payflux.transaction_service.controller;

import com.payflux.transaction_service.entity.Transaction;
import com.payflux.transaction_service.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    @PostMapping("/create")
    public ResponseEntity<?> create(
            @Valid @RequestBody Transaction transaction,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            transaction.setSenderId(Long.parseLong(userId));
            Transaction created = service.createTransaction(transaction);
            HttpStatus status = "FAILED".equals(created.getStatus())
                    ? HttpStatus.UNPROCESSABLE_ENTITY
                    : "REQUIRES_RECONCILIATION".equals(created.getStatus())
                            ? HttpStatus.SERVICE_UNAVAILABLE
                            : HttpStatus.OK;
            return ResponseEntity.status(status).body(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Transaction>> getByUser(
            @PathVariable("userId") Long userId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        // Allow if the caller is the same user or an admin
        if (headerUserId == null || headerUserId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Long callerId;
        try {
            callerId = Long.parseLong(headerUserId);
        } catch (NumberFormatException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!callerId.equals(userId) && !"ROLE_ADMIN".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(service.getTransactionsByUserId(userId));
    }

    @GetMapping("/all")
    public ResponseEntity<List<Transaction>> getAll(
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        if (!"ROLE_ADMIN".equals(role)) {
            return ResponseEntity.status(role == null ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(service.getAllTransactions());
    }
}

