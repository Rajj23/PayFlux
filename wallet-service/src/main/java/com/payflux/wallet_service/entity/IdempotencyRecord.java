package com.payflux.wallet_service.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private String operation; // CREDIT, DEBIT, HOLD

    @Column(columnDefinition = "TEXT")
    private String responseJson;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public IdempotencyRecord() {}

    public IdempotencyRecord(String idempotencyKey, String operation, String responseJson) {
        this.idempotencyKey = idempotencyKey;
        this.operation = operation;
        this.responseJson = responseJson;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }

    public String getResponseJson() { return responseJson; }
    public void setResponseJson(String responseJson) { this.responseJson = responseJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

