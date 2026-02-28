package com.payflux.wallet_service.scheduler;

import com.payflux.wallet_service.entity.WalletHold;
import com.payflux.wallet_service.repository.WalletHoldRepository;
import com.payflux.wallet_service.repository.WalletRepository;
import com.payflux.wallet_service.service.WalletService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class HoldExpiryScheduler {

    private final WalletHoldRepository walletHoldRepository;
    private final WalletService walletService;


    public HoldExpiryScheduler(WalletHoldRepository walletHoldRepository, WalletService walletService) {
        this.walletHoldRepository = walletHoldRepository;
        this.walletService = walletService;
    }

    @Scheduled(fixedRateString = "${wallet.hold.expiry.scan-rate-ms:60000}")
    public void expiredOldHolds(){
        LocalDateTime now = LocalDateTime.now();

        List<WalletHold> expired = walletHoldRepository.findByStatusAndExpiresAtBefore("ACTIVE", now);

        for(WalletHold hold:expired){
            String ref = hold.getHoldReference();

            try{
                walletService.releaseHold(ref);
                System.out.println("Expired hold released: "+ ref);
            }
            catch (Exception e){
                System.err.println("Failed to release expired hold: "+ref+" : "+e.getMessage());
            }
        }
    }
}
