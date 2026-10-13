package com.payflux.transaction_service.repository;

import com.payflux.transaction_service.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;


public interface TransactionRepository extends JpaRepository<Transaction,Long> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    List<Transaction> findBySenderIdOrReceiverIdOrderByTimeStampDesc(Long senderId, Long receiverId);
}
