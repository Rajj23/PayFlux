package com.paypal.user_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.paypal.user_service.dto.CreateWalletRequest;

@FeignClient(name = "wallet-service", url = "http://localhost:8083/api/v1/wallets")
public interface WalletClient {

    @PostMapping
    WalletClient createWallet(@RequestBody CreateWalletRequest request);
}
