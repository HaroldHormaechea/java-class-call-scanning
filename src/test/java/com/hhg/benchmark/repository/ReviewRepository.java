package com.hhg.benchmark.repository;

import com.hhg.benchmark.entity.Customer;
import com.hhg.benchmark.entity.Product;
import com.hhg.benchmark.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {

    List<Review> findByProduct(Product product);

    List<Review> findByProductId(Long productId);

    List<Review> findByCustomer(Customer customer);

    List<Review> findByCustomerId(Long customerId);

    List<Review> findByApproved(boolean approved);

    List<Review> findByProductIdAndApproved(Long productId, boolean approved);

    @Query("SELECT COALESCE(AVG(r.rating), 0.0) FROM Review r WHERE r.product.id = :productId")
    double findAverageRatingByProductId(@Param("productId") Long productId);
}
