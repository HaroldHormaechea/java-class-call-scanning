package com.hhg.benchmark.controller;

import com.hhg.benchmark.entity.Payment;
import com.hhg.benchmark.exception.NotFoundException;
import com.hhg.benchmark.exception.ValidationException;
import com.hhg.benchmark.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/invoice/{invoiceId}")
    public ResponseEntity<List<Payment>> findByInvoice(@PathVariable Long invoiceId) {
        return ResponseEntity.ok(paymentService.findByInvoice(invoiceId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> findById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(paymentService.findById(id));
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/invoice/{invoiceId}/total")
    public ResponseEntity<BigDecimal> getTotalPaid(@PathVariable Long invoiceId) {
        return ResponseEntity.ok(paymentService.getTotalPaid(invoiceId));
    }

    @PostMapping
    public ResponseEntity<?> processPayment(@RequestBody Map<String, Object> body) {
        try {
            Long invoiceId = Long.valueOf(body.get("invoiceId").toString());
            BigDecimal amount = body.get("amount") != null
                    ? new BigDecimal(body.get("amount").toString()) : null;
            String method = (String) body.get("method");
            String transactionId = (String) body.get("transactionId");
            Payment payment = paymentService.processPayment(invoiceId, amount, method, transactionId);
            return ResponseEntity.ok(payment);
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ValidationException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/{id}/refund")
    public ResponseEntity<?> refund(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(paymentService.refund(id));
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ValidationException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
