package com.payflux.wallet_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflux.wallet_service.dto.*;
import com.payflux.wallet_service.entity.IdempotencyRecord;
import com.payflux.wallet_service.entity.Transaction;
import com.payflux.wallet_service.entity.Wallet;
import com.payflux.wallet_service.entity.WalletHold;
import com.payflux.wallet_service.exception.InsufficientFundsException;
import com.payflux.wallet_service.repository.IdempotencyRepository;
import com.payflux.wallet_service.repository.TransactionRepository;
import com.payflux.wallet_service.repository.WalletHoldRepository;
import com.payflux.wallet_service.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class WalletServiceImplTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private WalletHoldRepository walletHoldRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private IdempotencyRepository idempotencyRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private WalletServiceImpl walletService;

    @Test
    void testCreateWallet_NewWallet() {
        CreateWalletRequest request = new CreateWalletRequest();
        request.setUserId(1L);
        request.setCurrency("USD");

        Wallet saved = new Wallet(1L, "USD");
        saved.setId(10L);
        saved.setBalance(0L);
        saved.setAvailableBalance(0L);

        when(walletRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(walletRepository.save(any(Wallet.class))).thenReturn(saved);

        WalletResponse response = walletService.createWallet(request);

        assertNotNull(response);
        assertEquals(10L, response.getId());
        assertEquals("USD", response.getCurrency());
    }

    @Test
    void testCreateWallet_ExistingWallet_ReturnsExisting() {
        CreateWalletRequest request = new CreateWalletRequest();
        request.setUserId(1L);
        request.setCurrency("USD");

        Wallet existing = new Wallet(1L, "USD");
        existing.setId(10L);
        existing.setBalance(500L);
        existing.setAvailableBalance(500L);

        when(walletRepository.findByUserId(1L)).thenReturn(Optional.of(existing));

        WalletResponse response = walletService.createWallet(request);

        assertNotNull(response);
        assertEquals(10L, response.getId());
        assertEquals(500L, response.getBalance());
        verify(walletRepository, never()).save(any());
    }

    @Test
    void testCredit_Success() throws Exception {
        CreditRequest request = new CreditRequest();
        request.setUserId(1L);
        request.setAmount(100L);
        request.setCurrency("USD");
        request.setIdempotencyKey("idem-credit-1");

        Wallet existing = new Wallet(1L, "USD");
        existing.setId(10L);
        existing.setBalance(50L);
        existing.setAvailableBalance(50L);

        when(idempotencyRepository.findByIdempotencyKey("idem-credit-1")).thenReturn(Optional.empty());
        when(walletRepository.findByUserIdAndCurrency(1L, "USD")).thenReturn(Optional.of(existing));
        when(walletRepository.save(any(Wallet.class))).thenReturn(existing);
        when(objectMapper.writeValueAsString(any(WalletResponse.class))).thenReturn("{}");

        WalletResponse response = walletService.credit(request);

        assertNotNull(response);
        assertEquals(150L, response.getBalance());
        assertEquals(150L, response.getAvailableBalance());

        verify(transactionRepository, times(1)).save(any(Transaction.class));
        verify(idempotencyRepository, times(1)).save(any(IdempotencyRecord.class));
    }

    @Test
    void testDebit_InsufficientFunds() {
        DebitRequest request = new DebitRequest();
        request.setUserId(1L);
        request.setAmount(100L);
        request.setCurrency("USD");

        Wallet existing = new Wallet(1L, "USD");
        existing.setId(10L);
        existing.setBalance(50L);
        existing.setAvailableBalance(50L);

        when(walletRepository.findByUserIdAndCurrency(1L, "USD")).thenReturn(Optional.of(existing));

        assertThrows(InsufficientFundsException.class, () -> walletService.debit(request));
    }

    @Test
    void testPlaceHold_Success() throws Exception {
        HoldRequest request = new HoldRequest();
        request.setUserId(1L);
        request.setAmount(50L);
        request.setCurrency("USD");
        request.setIdempotencyKey("idem-hold-1");

        Wallet existing = new Wallet(1L, "USD");
        existing.setId(10L);
        existing.setBalance(100L);
        existing.setAvailableBalance(100L);

        WalletHold savedHold = new WalletHold();
        savedHold.setHoldReference("HOLD-123");
        savedHold.setAmount(50L);
        savedHold.setStatus("ACTIVE");

        when(idempotencyRepository.findByIdempotencyKey("idem-hold-1")).thenReturn(Optional.empty());
        when(walletRepository.findByUserIdAndCurrency(1L, "USD")).thenReturn(Optional.of(existing));
        when(walletHoldRepository.save(any(WalletHold.class))).thenReturn(savedHold);
        when(objectMapper.writeValueAsString(any(HoldResponse.class))).thenReturn("{}");

        HoldResponse response = walletService.placeHold(request);

        assertNotNull(response);
        assertEquals("ACTIVE", response.getStatus());
        assertEquals(100L, existing.getBalance(), "Actual balance should remain the same");
        assertEquals(50L, existing.getAvailableBalance(), "Available balance should decrease");
    }

    @Test
    void testCaptureHold_Success() {
        CaptureRequest request = new CaptureRequest();
        request.setHoldReference("HOLD-123");

        Wallet wallet = new Wallet(1L, "USD");
        wallet.setId(10L);
        wallet.setBalance(100L);
        wallet.setAvailableBalance(50L);

        WalletHold activeHold = new WalletHold();
        activeHold.setWallet(wallet);
        activeHold.setAmount(50L);
        activeHold.setStatus("ACTIVE");
        activeHold.setHoldReference("HOLD-123");

        when(walletHoldRepository.findByHoldReference("HOLD-123")).thenReturn(Optional.of(activeHold));

        WalletResponse response = walletService.captureHold(request);

        assertNotNull(response);
        assertEquals(50L, response.getBalance(), "Balance after capture should decrease");
        assertEquals("CAPTURED", activeHold.getStatus());
    }

    @Test
    void testReleaseHold_Success() {
        Wallet wallet = new Wallet(1L, "USD");
        wallet.setId(10L);
        wallet.setBalance(100L);
        wallet.setAvailableBalance(50L);

        WalletHold activeHold = new WalletHold();
        activeHold.setWallet(wallet);
        activeHold.setAmount(50L);
        activeHold.setStatus("ACTIVE");
        activeHold.setHoldReference("HOLD-123");

        when(walletHoldRepository.findByHoldReference("HOLD-123")).thenReturn(Optional.of(activeHold));

        HoldResponse response = walletService.releaseHold("HOLD-123");

        assertNotNull(response);
        assertEquals("RELEASED", activeHold.getStatus());
        assertEquals("RELEASED", response.getStatus());
        assertEquals(100L, wallet.getAvailableBalance(), "Available balance should restore after release");
    }
}
