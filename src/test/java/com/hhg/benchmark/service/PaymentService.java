package com.hhg.benchmark.service;

import com.hhg.benchmark.entity.Invoice;
import com.hhg.benchmark.entity.Payment;
import com.hhg.benchmark.exception.NotFoundException;
import com.hhg.benchmark.exception.ValidationException;
import com.hhg.benchmark.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final InvoiceService invoiceService;

    public PaymentService(PaymentRepository paymentRepository, InvoiceService invoiceService) {
        this.paymentRepository = paymentRepository;
        this.invoiceService = invoiceService;
    }

    @Transactional
    public Payment processPayment(Long invoiceId, BigDecimal amount, String method, String transactionId) {
        Invoice invoice = invoiceService.findById(invoiceId);
        if ("PAID".equals(invoice.getStatus())) {
            throw new ValidationException("Invoice is already paid");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Payment amount must be greater than zero");
        }
        if (transactionId != null && !transactionId.isBlank()
                && paymentRepository.findByTransactionId(transactionId).isPresent()) {
            throw new ValidationException("Duplicate transaction id: " + transactionId);
        }
        Payment payment = new Payment();
        payment.setInvoice(invoice);
        payment.setAmount(amount);
        payment.setPaymentDate(LocalDateTime.now());
        payment.setMethod(method);
        payment.setStatus("COMPLETED");
        payment.setTransactionId(transactionId);
        paymentRepository.save(payment);
        invoiceService.markPaid(invoiceId);
        return payment;
    }

    @Transactional
    public Payment refund(Long id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Payment", id));
        if (!"COMPLETED".equals(payment.getStatus())) {
            throw new ValidationException("Only completed payments can be refunded");
        }
        payment.setStatus("REFUNDED");
        return paymentRepository.save(payment);
    }

    public Payment findById(Long id) {
        return paymentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Payment", id));
    }

    public List<Payment> findByInvoice(Long invoiceId) {
        return paymentRepository.findByInvoiceId(invoiceId);
    }

    public BigDecimal getTotalPaid(Long invoiceId) {
        return findByInvoice(invoiceId).stream()
                .filter(p -> "COMPLETED".equals(p.getStatus()))
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
