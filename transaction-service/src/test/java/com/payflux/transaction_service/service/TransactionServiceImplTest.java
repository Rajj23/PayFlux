package com.payflux.transaction_service.service;

import com.payflux.transaction_service.client.WalletRestClient;
import com.payflux.transaction_service.entity.Transaction;
import com.payflux.transaction_service.kafka.KafkaEventProducer;
import com.payflux.transaction_service.repository.TransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TransactionServiceImplTest {

    @Mock
    private TransactionRepository repository;

    @Mock
    private KafkaEventProducer kafkaEventProducer;

    @Mock
    private WalletRestClient walletRestClient;

    private TransactionServiceImpl transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionServiceImpl(repository, kafkaEventProducer, walletRestClient,
                new SimpleMeterRegistry());
    }

    @Test
    void testCreateTransaction_IdempotencyHit() {
        Transaction request = new Transaction();
        request.setIdempotencyKey("test-key");

        Transaction existing = new Transaction();
        existing.setId(10L);
        existing.setIdempotencyKey("test-key");

        when(repository.findByIdempotencyKey("test-key")).thenReturn(Optional.of(existing));

        Transaction result = transactionService.createTransaction(request);

        assertEquals(10L, result.getId());
        verify(walletRestClient, never()).holdWallet(anyLong(), anyLong(), anyString());
    }

    @Test
    void testCreateTransaction_SuccessFlow() {
        Transaction request = new Transaction();
        request.setSenderId(1L);
        request.setReceiverId(2L);
        request.setAmount(50.0);

        Transaction savedInitial = new Transaction();
        savedInitial.setId(10L);
        savedInitial.setSenderId(1L);
        savedInitial.setReceiverId(2L);
        savedInitial.setAmount(50.0);

        when(repository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(Transaction.class))).thenReturn(savedInitial);

        when(walletRestClient.holdWallet(eq(1L), eq(50L), anyString())).thenReturn("hold-123");
        doNothing().when(walletRestClient).captureHold("hold-123");
        doNothing().when(walletRestClient).creditWallet(eq(2L), eq(50L), anyString());
        doNothing().when(kafkaEventProducer).sendTransactionEvent(eq("10"), any(Transaction.class));

        Transaction result = transactionService.createTransaction(request);

        assertNotNull(result);
        assertEquals(10L, result.getId());
        assertEquals("SUCCESS", result.getStatus());
        verify(walletRestClient, times(1)).holdWallet(eq(1L), eq(50L), anyString());
        verify(walletRestClient, times(1)).creditWallet(eq(2L), eq(50L), anyString());
        verify(walletRestClient, times(1)).captureHold("hold-123");
        verify(kafkaEventProducer, times(1)).sendTransactionEvent(eq("10"), any(Transaction.class));
    }

    @Test
    void testCreateTransaction_HoldFailsAndPersistsFailure() {
        Transaction request = new Transaction();
        request.setSenderId(1L);
        request.setAmount(50.0);

        Transaction savedInitial = new Transaction();
        savedInitial.setId(10L);

        when(repository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(Transaction.class))).thenReturn(savedInitial);

        when(walletRestClient.holdWallet(eq(1L), eq(50L), anyString()))
                .thenThrow(new RuntimeException("Insufficient funds"));

        Transaction result = transactionService.createTransaction(request);
        assertEquals("FAILED", result.getStatus());
        verify(repository, times(2)).save(savedInitial);
    }

    @Test
    void testCreateTransaction_CaptureFailureRequiresReconciliationWithoutCreditingReceiver() {
        Transaction request = new Transaction();
        request.setSenderId(1L);
        request.setReceiverId(2L);
        request.setAmount(50.0);

        Transaction savedInitial = new Transaction();
        savedInitial.setId(10L);

        when(repository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(Transaction.class))).thenReturn(savedInitial);

        when(walletRestClient.holdWallet(anyLong(), anyLong(), anyString())).thenReturn("hold-123");
        doThrow(new RuntimeException("Capture response unavailable")).when(walletRestClient).captureHold("hold-123");

        Transaction result = transactionService.createTransaction(request);
        assertEquals("REQUIRES_RECONCILIATION", result.getStatus());
        verify(walletRestClient, never()).creditWallet(anyLong(), anyLong(), anyString());
        verify(kafkaEventProducer, never()).sendTransactionEvent(anyString(), any(Transaction.class));
    }

    @Test
    void testCreateTransaction_CreditFailureRequiresReconciliation() {
        Transaction request = new Transaction();
        request.setSenderId(1L);
        request.setReceiverId(2L);
        request.setAmount(50.0);

        Transaction savedInitial = new Transaction();
        savedInitial.setId(10L);

        when(repository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(Transaction.class))).thenReturn(savedInitial);
        when(walletRestClient.holdWallet(anyLong(), anyLong(), anyString())).thenReturn("hold-123");
        doNothing().when(walletRestClient).captureHold("hold-123");
        doThrow(new RuntimeException("Receiver unavailable")).when(walletRestClient)
                .creditWallet(eq(2L), eq(50L), anyString());

        Transaction result = transactionService.createTransaction(request);

        assertEquals("REQUIRES_RECONCILIATION", result.getStatus());
        verify(walletRestClient).captureHold("hold-123");
        verify(kafkaEventProducer, never()).sendTransactionEvent(anyString(), any(Transaction.class));
    }

    @Test
    void testCreateTransaction_RejectsFractionalMoneyWithoutCallingWallet() {
        Transaction request = new Transaction();
        request.setSenderId(1L);
        request.setReceiverId(2L);
        request.setAmount(50.25);

        assertThrows(IllegalArgumentException.class, () -> transactionService.createTransaction(request));
        verify(walletRestClient, never()).holdWallet(anyLong(), anyLong(), anyString());
        verify(repository, never()).save(any(Transaction.class));
    }
}
