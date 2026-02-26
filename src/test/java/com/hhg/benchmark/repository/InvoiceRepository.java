package com.hhg.benchmark.repository;

import com.hhg.benchmark.entity.Customer;
import com.hhg.benchmark.entity.CustomerOrder;
import com.hhg.benchmark.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    Optional<Invoice> findByOrder(CustomerOrder order);

    Optional<Invoice> findByOrderId(Long orderId);

    List<Invoice> findByCustomer(Customer customer);

    List<Invoice> findByCustomerId(Long customerId);

    List<Invoice> findByStatus(String status);

    List<Invoice> findByDueDateBefore(LocalDate date);
}
