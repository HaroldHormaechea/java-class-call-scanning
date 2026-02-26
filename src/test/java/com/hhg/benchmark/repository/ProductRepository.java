package com.hhg.benchmark.repository;

import com.hhg.benchmark.entity.Category;
import com.hhg.benchmark.entity.Product;
import com.hhg.benchmark.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByActive(boolean active);

    List<Product> findByCategory(Category category);

    List<Product> findBySupplier(Supplier supplier);

    List<Product> findByNameContainingIgnoreCase(String name);

    List<Product> findByStockQuantityLessThan(int threshold);

    Optional<Product> findByIdAndActive(Long id, boolean active);
}
