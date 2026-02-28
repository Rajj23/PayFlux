package com.payflux.wallet_service.dto;

public class HoldResponse {
    private String holdReference;
    private Long amount;
    private String status;

    public HoldResponse(String holdReference, Long amount, String status) {
        this.holdReference = holdReference;
        this.amount = amount;
        this.status = status;
    }

    public String getHoldReference() {
        return holdReference;
    }

    public void setHoldReference(String holdReference) {
        this.holdReference = holdReference;
    }

    public Long getAmount() {
        return amount;
    }

    public void setAmount(Long amount) {
        this.amount = amount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
