package com.hhg.benchmark.repository;

import com.hhg.benchmark.entity.Invoice;
import com.hhg.benchmark.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByInvoice(Invoice invoice);

    List<Payment> findByInvoiceId(Long invoiceId);

    List<Payment> findByStatus(String status);

    Optional<Payment> findByTransactionId(String transactionId);
}
