package com.payflux.transaction_service.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

@Entity
@Table(name = "transaction")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sender_id",nullable = false)
    private Long senderId;

    @Column(name = "receiver_id",nullable = false)
    private Long receiverId;

    @Column(nullable = false)
    @Positive(message = "Amount must be positive")
    private Double amount;

    @Column(nullable = false)
    private LocalDateTime timeStamp;

    @Column(nullable = false)
    private String status;

    public Transaction(Long senderId, Long id, Long receiverId, Double amount, LocalDateTime timeStamp, String status) {
        this.senderId = senderId;
        this.id = id;
        this.receiverId = receiverId;
        this.amount = amount;
        this.timeStamp = timeStamp;
        this.status = status;
    }

    public Transaction() {

    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getTimeStamp() {
        return timeStamp;
    }

    public void setTimeStamp(LocalDateTime timeStamp) {
        this.timeStamp = timeStamp;
    }

    public Double getAmount() {
        return amount;
    }

    public void setAmount(Double amount) {
        this.amount = amount;
    }

    public Long getSenderId() {
        return senderId;
    }

    public void setSenderId(Long senderId) {
        this.senderId = senderId;
    }

    public Long getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(Long receiverId) {
        this.receiverId = receiverId;
    }

    @PrePersist
    public void prePersist(){
        if(timeStamp == null){
            timeStamp = LocalDateTime.now();
        }
        if(status == null){
            status = "Pending";
        }
    }

    @Override
    public String toString() {
        return "Transactions{" +
                "id=" + id +
                ", senderId=" + senderId +
                ", receiverId=" + receiverId +
                ", amount=" + amount +
                ", timeStamp=" + timeStamp +
                ", status='" + status + '\'' +
                '}';
    }
}
