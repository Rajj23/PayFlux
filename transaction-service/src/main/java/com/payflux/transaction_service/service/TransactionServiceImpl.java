package com.payflux.transaction_service.service;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.payflux.transaction_service.client.WalletRestClient;
import com.payflux.transaction_service.entity.Transaction;
import com.payflux.transaction_service.kafka.KafkaEventProducer;
import com.payflux.transaction_service.repository.TransactionRepository;

import io.micrometer.core.instrument.MeterRegistry;

@Service
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepository repository;
    private final KafkaEventProducer kafkaEventProducer;
    private final WalletRestClient walletRestClient;
    private final MeterRegistry meterRegistry;

    public TransactionServiceImpl(TransactionRepository repository,
                                  KafkaEventProducer kafkaEventProducer,
                                  WalletRestClient walletRestClient,
                                  MeterRegistry meterRegistry) {
        this.repository = repository;
        this.kafkaEventProducer = kafkaEventProducer;
        this.walletRestClient = walletRestClient;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Transaction createTransaction(Transaction request) {
        if (request.getSenderId() == null || request.getReceiverId() == null
                || request.getAmount() == null || !Double.isFinite(request.getAmount())
                || request.getSenderId().equals(request.getReceiverId())) {
            throw new IllegalArgumentException("Sender, receiver, and a finite amount for a different recipient are required");
        }
        BigDecimal amount = BigDecimal.valueOf(request.getAmount()).stripTrailingZeros();
        if (amount.signum() <= 0 || amount.scale() > 0 || amount.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("Amount must be a positive whole number of INR");
        }
        // Generate idempotency key if not provided
        String idempotencyKey = request.getIdempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            idempotencyKey = UUID.randomUUID().toString();
        }

        // Idempotency check — return existing transaction if already processed
        Optional<Transaction> existing = repository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            System.out.println("Transaction idempotency hit for key: " + idempotencyKey);
            return existing.get();
        }

        Long senderId = request.getSenderId();
        Long receiverId = request.getReceiverId();
        Double amountValue = request.getAmount();
        Long amountInLong = amount.longValueExact();

        Transaction transaction = new Transaction();
        transaction.setSenderId(senderId);
        transaction.setReceiverId(receiverId);
        transaction.setAmount(amountValue);
        transaction.setTimeStamp(LocalDateTime.now());
        transaction.setStatus("PENDING");
        transaction.setIdempotencyKey(idempotencyKey);
        Transaction saved = repository.save(transaction);

        String holdIdempotencyKey = "txn-" + saved.getId() + "-hold";
        String creditIdempotencyKey = "txn-" + saved.getId() + "-credit";

        // Step 1: Place hold on sender's wallet (reserves funds without debiting)
        String holdReference;
        try {
            holdReference = walletRestClient.holdWallet(senderId, amountInLong, holdIdempotencyKey);
            saved.setHoldReference(holdReference);
            repository.save(saved);
        } catch (Exception e) {
            saved.setStatus("FAILED");
            repository.save(saved);
            meterRegistry.counter("payments", "status", "failed").increment();
            return saved;
        }

        // Capture before crediting the receiver. A capture error has an ambiguous
        // outcome across the network, so do not claim success or release the hold.
        try {
            walletRestClient.captureHold(holdReference);
        } catch (Exception e) {
            saved.setStatus("REQUIRES_RECONCILIATION");
            repository.save(saved);
            meterRegistry.counter("payments", "status", "reconciliation_required").increment();
            return saved;
        }

        // Credit only after the sender debit is confirmed. The stable idempotency
        // key lets an operator safely retry if the remote result is ambiguous.
        try {
            walletRestClient.creditWallet(receiverId, amountInLong, creditIdempotencyKey);
        } catch (Exception e) {
            saved.setStatus("REQUIRES_RECONCILIATION");
            repository.save(saved);
            meterRegistry.counter("payments", "status", "reconciliation_required").increment();
            return saved;
        }

        saved.setStatus("SUCCESS");
        repository.save(saved);
        meterRegistry.counter("payments", "status", "success").increment();

        // Publish Kafka event for notifications/rewards
        try {
            String key = String.valueOf(saved.getId());
            kafkaEventProducer.sendTransactionEvent(key, saved);
        } catch (Exception e) {
            System.err.println("Failed to send Kafka event: " + e.getMessage());
        }

        return saved;
    }


    @Override
    public List<Transaction> getAllTransactions() {
        return repository.findAll();
    }

    @Override
    public List<Transaction> getTransactionsByUserId(Long userId) {
        return repository.findBySenderIdOrReceiverIdOrderByTimeStampDesc(userId, userId);
    }
}
