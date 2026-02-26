package com.hhg.benchmark.repository;

import com.hhg.benchmark.entity.Customer;
import com.hhg.benchmark.entity.CustomerOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {

    List<CustomerOrder> findByCustomer(Customer customer);

    List<CustomerOrder> findByCustomerId(Long customerId);

    List<CustomerOrder> findByStatus(String status);

    List<CustomerOrder> findByCustomerAndStatus(Customer customer, String status);

    List<CustomerOrder> findByOrderDateBetween(LocalDateTime from, LocalDateTime to);
}
