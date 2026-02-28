package com.payflux.transaction_service.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import com.payflux.transaction_service.entity.Transaction;
import com.payflux.transaction_service.kafka.KafkaEventProducer;
import com.payflux.transaction_service.repository.TransactionRepository;

@Service
public class TransactionServiceImpl implements TransactionService {

    private static final String WALLET_SERVICE_URL = "http://localhost:8083/api/v1/wallets";

    private final TransactionRepository repository;
    private final KafkaEventProducer kafkaEventProducer;
    private final RestTemplate restTemplate;

    public TransactionServiceImpl(TransactionRepository repository,
                                  KafkaEventProducer kafkaEventProducer,
                                  RestTemplate restTemplate) {
        this.repository = repository;
        this.kafkaEventProducer = kafkaEventProducer;
        this.restTemplate = restTemplate;
    }

    @Override
    @Transactional
    public Transaction createTransaction(Transaction request) {
        Long senderId = request.getSenderId();
        Long receiverId = request.getReceiverId();
        Double amount = request.getAmount();
        Long amountInLong = Math.round(amount);

        Transaction transaction = new Transaction();
        transaction.setSenderId(senderId);
        transaction.setReceiverId(receiverId);
        transaction.setAmount(amount);
        transaction.setTimeStamp(LocalDateTime.now());
        transaction.setStatus("PENDING");
        Transaction saved = repository.save(transaction);

        try {
            debitWallet(senderId, amountInLong);
        } catch (Exception e) {
            saved.setStatus("FAILED");
            repository.save(saved);
            throw new RuntimeException("Debit failed for sender " + senderId + ": " + e.getMessage());
        }

        try {
            creditWallet(receiverId, amountInLong);
        } catch (Exception e) {
            creditWallet(senderId, amountInLong);
            saved.setStatus("FAILED");
            repository.save(saved);
            throw new RuntimeException("Credit failed for receiver " + receiverId + ": " + e.getMessage());
        }

        saved.setStatus("SUCCESS");
        repository.save(saved);

        try {
            String key = String.valueOf(saved.getId());
            kafkaEventProducer.sendTransactionEvent(key, saved);
        } catch (Exception e) {
            System.err.println("Failed to send Kafka event: " + e.getMessage());
        }

        return saved;
    }

    private void debitWallet(Long userId, Long amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        body.put("currency", "INR");
        body.put("amount", amount);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        restTemplate.postForEntity(WALLET_SERVICE_URL + "/debit", request, String.class);
    }

    private void creditWallet(Long userId, Long amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        body.put("currency", "INR");
        body.put("amount", amount);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        restTemplate.postForEntity(WALLET_SERVICE_URL + "/credit", request, String.class);
    }

    @Override
    public List<Transaction> getAllTransactions() {
        return repository.findAll();
    }
}
