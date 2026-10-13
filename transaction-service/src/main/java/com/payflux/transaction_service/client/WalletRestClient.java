package com.payflux.transaction_service.client;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@Component
public class WalletRestClient {

    private final String walletServiceUrl;
    private final RestTemplate restTemplate;

    public WalletRestClient(RestTemplate restTemplate,
                            @org.springframework.beans.factory.annotation.Value("${wallet.service.url}") String walletServiceUrl) {
        this.restTemplate = restTemplate;
        this.walletServiceUrl = walletServiceUrl;
    }

    @Retryable(retryFor = ResourceAccessException.class, maxAttempts = 3, backoff = @Backoff(delay = 500, multiplier = 2))
    public String holdWallet(Long userId, Long amount, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        body.put("currency", "INR");
        body.put("amount", amount);
        body.put("idempotencyKey", idempotencyKey);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(walletServiceUrl + "/hold", request, Map.class);

        Map responseBody = response.getBody();
        if (responseBody == null || !responseBody.containsKey("holdReference")) {
            throw new RuntimeException("Hold response missing holdReference");
        }
        return (String) responseBody.get("holdReference");
    }

    @Retryable(retryFor = ResourceAccessException.class, maxAttempts = 3, backoff = @Backoff(delay = 500, multiplier = 2))
    public void creditWallet(Long userId, Long amount, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        body.put("currency", "INR");
        body.put("amount", amount);
        body.put("idempotencyKey", idempotencyKey);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        restTemplate.postForEntity(walletServiceUrl + "/credit", request, String.class);
    }

    @Retryable(retryFor = ResourceAccessException.class, maxAttempts = 3, backoff = @Backoff(delay = 500, multiplier = 2))
    public void captureHold(String holdReference) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("holdReference", holdReference);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        restTemplate.postForEntity(walletServiceUrl + "/capture", request, String.class);
    }

    @Retryable(retryFor = ResourceAccessException.class, maxAttempts = 3, backoff = @Backoff(delay = 500, multiplier = 2))
    public void releaseHold(String holdReference) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        restTemplate.postForEntity(walletServiceUrl + "/release/" + holdReference, null, String.class);
    }
}

