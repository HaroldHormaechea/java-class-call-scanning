package com.hhg.benchmark.repository;

import com.hhg.benchmark.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    List<Supplier> findByActive(boolean active);

    Optional<Supplier> findByEmail(String email);

    List<Supplier> findByNameContainingIgnoreCase(String name);
}
