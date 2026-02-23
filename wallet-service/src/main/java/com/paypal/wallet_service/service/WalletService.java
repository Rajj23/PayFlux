package com.paypal.wallet_service.service;

import com.paypal.wallet_service.dto.*;

public interface WalletService {

    public WalletResponse createWallet(CreateWalletRequest request);
    public WalletResponse credit(CreditRequest request);
    public WalletResponse debit(DebitRequest request);
    public WalletResponse getWallet(Long userId);
    public HoldResponse placeHold(HoldRequest request);
    public WalletResponse captureHold(CaptureRequest request);
    public HoldResponse releaseHold(String holdReference);
}
