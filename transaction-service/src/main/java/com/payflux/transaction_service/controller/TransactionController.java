package com.payflux.transaction_service.controller;

import com.payflux.transaction_service.entity.Transaction;
import com.payflux.transaction_service.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    @PostMapping("/create")
    public ResponseEntity<?> create(@Valid @RequestBody Transaction transaction){

        Transaction created = service.createTransaction(transaction);
        return ResponseEntity.ok(created);
    }

    @GetMapping("/all")
    public List<Transaction> getAll(){
        return service.getAllTransactions();
    }
}
