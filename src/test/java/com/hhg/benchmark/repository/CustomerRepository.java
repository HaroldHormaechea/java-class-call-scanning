package com.hhg.benchmark.repository;

import com.hhg.benchmark.entity.Customer;
import com.hhg.benchmark.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByUser(User user);

    Optional<Customer> findByUserId(Long userId);

    List<Customer> findByLastNameContainingIgnoreCase(String lastName);
}
