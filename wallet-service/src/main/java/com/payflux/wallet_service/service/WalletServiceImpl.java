package com.payflux.wallet_service.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflux.wallet_service.dto.CaptureRequest;
import com.payflux.wallet_service.dto.CreateWalletRequest;
import com.payflux.wallet_service.dto.CreditRequest;
import com.payflux.wallet_service.dto.DebitRequest;
import com.payflux.wallet_service.dto.HoldRequest;
import com.payflux.wallet_service.dto.HoldResponse;
import com.payflux.wallet_service.dto.TransferRequest;
import com.payflux.wallet_service.dto.WalletResponse;
import com.payflux.wallet_service.entity.IdempotencyRecord;
import com.payflux.wallet_service.entity.Transaction;
import com.payflux.wallet_service.entity.Wallet;
import com.payflux.wallet_service.entity.WalletHold;
import com.payflux.wallet_service.exception.InsufficientFundsException;
import com.payflux.wallet_service.exception.NotFoundException;
import com.payflux.wallet_service.repository.IdempotencyRepository;
import com.payflux.wallet_service.repository.TransactionRepository;
import com.payflux.wallet_service.repository.WalletHoldRepository;
import com.payflux.wallet_service.repository.WalletRepository;

import jakarta.transaction.Transactional;

@Service
public class WalletServiceImpl implements WalletService{

    private static final long SIGNUP_BONUS_INR = 1_000L;

    private final WalletRepository walletRepository;
    private final WalletHoldRepository walletHoldRepository;
    private final TransactionRepository transactionRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public WalletServiceImpl(WalletRepository walletRepository,
                             WalletHoldRepository walletHoldRepository,
                             TransactionRepository transactionRepository,
                             IdempotencyRepository idempotencyRepository,
                             ObjectMapper objectMapper) {
        this.walletRepository = walletRepository;
        this.walletHoldRepository = walletHoldRepository;
        this.transactionRepository = transactionRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public WalletResponse createWallet(CreateWalletRequest request) {
        // Return existing wallet if one already exists for this user
        Optional<Wallet> existing = walletRepository.findByUserId(request.getUserId());
        if (existing.isPresent()) {
            Wallet w = existing.get();
            return new WalletResponse(w.getId(), w.getUserId(), w.getCurrency(), w.getBalance(), w.getAvailableBalance());
        }

        Wallet wallet = new Wallet(request.getUserId(), request.getCurrency());
        if ("INR".equalsIgnoreCase(request.getCurrency())) {
            wallet.setBalance(SIGNUP_BONUS_INR);
            wallet.setAvailableBalance(SIGNUP_BONUS_INR);
        }
        Wallet saved = walletRepository.save(wallet);
        if ("INR".equalsIgnoreCase(request.getCurrency())) {
            transactionRepository.save(
                    new Transaction(saved.getId(), "CREDIT", SIGNUP_BONUS_INR, "SUCCESS")
            );
        }
        return new WalletResponse(
                saved.getId(), saved.getUserId(), saved.getCurrency(),
                saved.getBalance(), saved.getAvailableBalance()
        );
    }

    @Override
    @Transactional
    public WalletResponse credit(CreditRequest request) {
        // Idempotency check
        if (request.getIdempotencyKey() != null) {
            Optional<IdempotencyRecord> existing = idempotencyRepository.findByIdempotencyKey(request.getIdempotencyKey());
            if (existing.isPresent()) {
                System.out.println("CREDIT idempotency hit for key: " + request.getIdempotencyKey());
                return parseWalletResponse(existing.get().getResponseJson());
            }
        }

        System.out.println("CREDIT request received: userId= "+request.getUserId()+
                ", amount= "+request.getAmount()+
                ", currency= "+request.getCurrency());

        Wallet wallet = walletRepository.findByUserIdAndCurrency(request.getUserId(), request.getCurrency())
                .orElseThrow(()-> new NotFoundException("Wallet not found for user: "+request.getUserId()));
        wallet.setBalance(wallet.getBalance() + request.getAmount());
        wallet.setAvailableBalance(wallet.getAvailableBalance()+request.getAmount());

        Wallet saved = walletRepository.save(wallet);
        Long amount = request.getAmount();
        transactionRepository.save(
                new Transaction(wallet.getId(),"CREDIT",amount,"SUCCESS")
        );

        System.out.println("CREDIT done: walletId=" + saved.getId() +
                ", newBalance=" + saved.getBalance() +
                ", availableBalance=" + saved.getAvailableBalance());

        WalletResponse response = new WalletResponse(saved.getId(),saved.getUserId(),
                saved.getCurrency(),saved.getBalance(),saved.getAvailableBalance());

        // Save idempotency record
        if (request.getIdempotencyKey() != null) {
            saveIdempotencyRecord(request.getIdempotencyKey(), "CREDIT", response);
        }

        return response;
    }

    @Override
    @Transactional
    public WalletResponse debit(DebitRequest request) {
        // Idempotency check
        if (request.getIdempotencyKey() != null) {
            Optional<IdempotencyRecord> existing = idempotencyRepository.findByIdempotencyKey(request.getIdempotencyKey());
            if (existing.isPresent()) {
                System.out.println("DEBIT idempotency hit for key: " + request.getIdempotencyKey());
                return parseWalletResponse(existing.get().getResponseJson());
            }
        }

        System.out.println("DEBIT request received: userId= "+request.getUserId()+
                ", amount= "+request.getAmount()+
                ", currency= "+request.getCurrency());

        Wallet wallet = walletRepository.findByUserIdAndCurrency(request.getUserId(), request.getCurrency())
                .orElseThrow(()-> new NotFoundException("Wallet not found found for user: "+request.getUserId()));

        if(wallet.getAvailableBalance()<request.getAmount()){
            throw new InsufficientFundsException("Not enough balance");
        }

        wallet.setBalance(wallet.getBalance() - request.getAmount());
        wallet.setAvailableBalance(wallet.getAvailableBalance() - request.getAmount());
        Wallet saved = walletRepository.save(wallet);

        System.out.println("DEBIT done: walletId=" + saved.getId() +
                ", newBalance=" + saved.getBalance() +
                ", availableBalance=" + saved.getAvailableBalance());

        WalletResponse response = new WalletResponse(saved.getId(),saved.getUserId(),
                saved.getCurrency(),saved.getBalance(),saved.getAvailableBalance());

        // Save idempotency record
        if (request.getIdempotencyKey() != null) {
            saveIdempotencyRecord(request.getIdempotencyKey(), "DEBIT", response);
        }

        return response;
    }

    @Override
    @Transactional
    public void transfer(TransferRequest request) {
        if (request == null || request.getSenderId() == null || request.getReceiverId() == null
                || request.getSenderId().equals(request.getReceiverId())
                || request.getAmount() == null || request.getAmount() <= 0
                || request.getCurrency() == null || request.getCurrency().isBlank()
                || request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()) {
            throw new IllegalArgumentException("A valid sender, receiver, currency, positive amount, and idempotency key are required");
        }

        Optional<IdempotencyRecord> prior = idempotencyRepository
                .findByIdempotencyKey(request.getIdempotencyKey());
        if (prior.isPresent()) {
            IdempotencyRecord record = prior.get();
            if (!"TRANSFER".equals(record.getOperation())) {
                throw new IllegalArgumentException("Idempotency key was already used for another operation");
            }
            TransferRequest original = parseTransferRequest(record.getResponseJson());
            if (!sameTransfer(original, request)) {
                throw new IllegalArgumentException("Idempotency key was already used for a different transfer");
            }
            return;
        }

        List<Wallet> wallets = walletRepository.findByUserIdInAndCurrencyOrderByUserIdAsc(
                List.of(request.getSenderId(), request.getReceiverId()), request.getCurrency());
        Wallet sender = wallets.stream()
                .filter(wallet -> wallet.getUserId().equals(request.getSenderId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Sender wallet not found"));
        Wallet receiver = wallets.stream()
                .filter(wallet -> wallet.getUserId().equals(request.getReceiverId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Receiver wallet not found"));

        if (sender.getAvailableBalance() < request.getAmount()) {
            throw new InsufficientFundsException("Not enough balance");
        }

        long senderBalance = Math.subtractExact(sender.getBalance(), request.getAmount());
        long senderAvailable = Math.subtractExact(sender.getAvailableBalance(), request.getAmount());
        long receiverBalance = Math.addExact(receiver.getBalance(), request.getAmount());
        long receiverAvailable = Math.addExact(receiver.getAvailableBalance(), request.getAmount());

        sender.setBalance(senderBalance);
        sender.setAvailableBalance(senderAvailable);
        receiver.setBalance(receiverBalance);
        receiver.setAvailableBalance(receiverAvailable);
        walletRepository.save(sender);
        walletRepository.save(receiver);
        transactionRepository.save(new Transaction(sender.getId(), "TRANSFER_OUT", request.getAmount(), "SUCCESS"));
        transactionRepository.save(new Transaction(receiver.getId(), "TRANSFER_IN", request.getAmount(), "SUCCESS"));

        try {
            idempotencyRepository.saveAndFlush(new IdempotencyRecord(request.getIdempotencyKey(), "TRANSFER",
                    objectMapper.writeValueAsString(request)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not save transfer idempotency record", e);
        }
    }

    @Override
    public WalletResponse getWallet(Long userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(()-> new NotFoundException("Wallet not found for user: "+userId));

        return new WalletResponse(wallet.getId(),wallet.getUserId(),
                wallet.getCurrency(),wallet.getBalance(),wallet.getAvailableBalance());
    }

    @Override
    @Transactional
    public HoldResponse placeHold(HoldRequest request) {
        // Idempotency check
        if (request.getIdempotencyKey() != null) {
            Optional<IdempotencyRecord> existing = idempotencyRepository.findByIdempotencyKey(request.getIdempotencyKey());
            if (existing.isPresent()) {
                System.out.println("HOLD idempotency hit for key: " + request.getIdempotencyKey());
                return parseHoldResponse(existing.get().getResponseJson());
            }
        }

        Wallet wallet = walletRepository.findByUserIdAndCurrency(request.getUserId(),request.getCurrency())
                .orElseThrow(()-> new NotFoundException("Wallet not found for user: "+request.getUserId()));

        if(wallet.getAvailableBalance()<request.getAmount()){
            throw new InsufficientFundsException("Not enough balance to hold");
        }

        wallet.setAvailableBalance(wallet.getAvailableBalance() - request.getAmount());

        WalletHold hold = new WalletHold();
        hold.setWallet(wallet);
        hold.setAmount(request.getAmount());
        hold.setHoldReference("HOLD-"+System.currentTimeMillis());
        hold.setStatus("ACTIVE");
        hold.setExpiresAt(LocalDateTime.now().plusMinutes(5)); // Auto-expire after 5 minutes

        walletRepository.save((wallet));
        walletHoldRepository.save(hold);

        HoldResponse response = new HoldResponse(hold.getHoldReference(), hold.getAmount(), hold.getStatus());

        // Save idempotency record
        if (request.getIdempotencyKey() != null) {
            saveIdempotencyRecord(request.getIdempotencyKey(), "HOLD", response);
        }

        return response;
    }

    @Override
    @Transactional
    public WalletResponse captureHold(CaptureRequest request) {
        WalletHold hold = walletHoldRepository.findByHoldReference(request.getHoldReference())
                .orElseThrow(()-> new NotFoundException("Hold not found"));

        if("CAPTURED".equals(hold.getStatus())){
            // Already captured — idempotent return
            Wallet wallet = hold.getWallet();
            return new WalletResponse(wallet.getId(),wallet.getUserId(),
                    wallet.getCurrency(),wallet.getBalance(),wallet.getAvailableBalance());
        }

        if(!"ACTIVE".equals(hold.getStatus())){
            throw new InsufficientFundsException("Hold is not active");
        }

        Wallet wallet = hold.getWallet();
        wallet.setBalance(wallet.getBalance() - hold.getAmount());

        hold.setStatus("CAPTURED");
        walletRepository.save(wallet);
        walletHoldRepository.save(hold);

        return new WalletResponse(wallet.getId(),wallet.getUserId(),
                wallet.getCurrency(),wallet.getBalance(),wallet.getAvailableBalance());
    }

    @Override
    @Transactional
    public HoldResponse releaseHold(String holdReference) {
        WalletHold hold = walletHoldRepository.findByHoldReference(holdReference)
                .orElseThrow(()-> new NotFoundException("Hold not found"));

        if("RELEASED".equals(hold.getStatus())){
            // Already released — idempotent return
            return new HoldResponse(hold.getHoldReference(), hold.getAmount(), hold.getStatus());
        }

        if(!"ACTIVE".equals(hold.getStatus())){
            throw new IllegalStateException("Hold is not active");
        }

        Wallet wallet = hold.getWallet();
        wallet.setAvailableBalance(wallet.getAvailableBalance() + hold.getAmount());

        hold.setStatus("RELEASED");
        walletRepository.save(wallet);
        walletHoldRepository.save(hold);

        return new HoldResponse(hold.getHoldReference(), hold.getAmount(), hold.getStatus());
    }

    // --- Helper methods for idempotency ---

    private void saveIdempotencyRecord(String key, String operation, Object response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            idempotencyRepository.save(new IdempotencyRecord(key, operation, json));
        } catch (Exception e) {
            System.err.println("Failed to save idempotency record: " + e.getMessage());
        }
    }

    private WalletResponse parseWalletResponse(String json) {
        try {
            return objectMapper.readValue(json, WalletResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse cached WalletResponse", e);
        }
    }

    private HoldResponse parseHoldResponse(String json) {
        try {
            return objectMapper.readValue(json, HoldResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse cached HoldResponse", e);
        }
    }

    private TransferRequest parseTransferRequest(String json) {
        try {
            return objectMapper.readValue(json, TransferRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read transfer idempotency record", e);
        }
    }

    private boolean sameTransfer(TransferRequest first, TransferRequest second) {
        return first.getSenderId().equals(second.getSenderId())
                && first.getReceiverId().equals(second.getReceiverId())
                && first.getAmount().equals(second.getAmount())
                && first.getCurrency().equals(second.getCurrency());
    }
}
