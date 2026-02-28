package com.payflux.transaction_service.repository;

import com.payflux.transaction_service.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;


public interface TransactionRepository extends JpaRepository<Transaction,Long> {
}
