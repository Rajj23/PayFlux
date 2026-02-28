package com.payflux.transaction_service.dto;

public class TransferRequest {

    private String senderName;

    private String receiverName;

    private Double amount;


    public TransferRequest(String senderName, Double amount, String receiverName) {
        this.senderName = senderName;
        this.amount = amount;
        this.receiverName = receiverName;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public void setReceiverName(String receiverName) {
        this.receiverName = receiverName;
    }

    public Double getAmount() {
        return amount;
    }

    public void setAmount(Double amount) {
        this.amount = amount;
    }
}
