package com.hhg.benchmark.service;

import com.hhg.benchmark.entity.Customer;
import com.hhg.benchmark.entity.Product;
import com.hhg.benchmark.entity.Review;
import com.hhg.benchmark.exception.NotFoundException;
import com.hhg.benchmark.exception.ValidationException;
import com.hhg.benchmark.repository.ProductRepository;
import com.hhg.benchmark.repository.ReviewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ProductRepository productRepository;
    private final CustomerService customerService;

    public ReviewService(ReviewRepository reviewRepository,
                         ProductRepository productRepository,
                         CustomerService customerService) {
        this.reviewRepository = reviewRepository;
        this.productRepository = productRepository;
        this.customerService = customerService;
    }

    @Transactional
    public Review create(Long productId, Long customerId, int rating, String comment) {
        if (rating < 1 || rating > 5) {
            throw new ValidationException("Rating must be between 1 and 5");
        }
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("Product", productId));
        Customer customer = customerService.findById(customerId);

        Review review = new Review();
        review.setProduct(product);
        review.setCustomer(customer);
        review.setRating(rating);
        review.setComment(comment);
        review.setCreatedAt(LocalDateTime.now());
        review.setApproved(false);
        return reviewRepository.save(review);
    }

    @Transactional
    public Review update(Long id, int rating, String comment) {
        Review review = reviewRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Review", id));
        if (rating < 1 || rating > 5) {
            throw new ValidationException("Rating must be between 1 and 5");
        }
        review.setRating(rating);
        review.setComment(comment);
        return reviewRepository.save(review);
    }

    @Transactional
    public void delete(Long id) {
        reviewRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Review", id));
        reviewRepository.deleteById(id);
    }

    @Transactional
    public Review approve(Long id) {
        Review review = reviewRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Review", id));
        review.setApproved(true);
        return reviewRepository.save(review);
    }

    public Review findById(Long id) {
        return reviewRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Review", id));
    }

    public List<Review> findByProduct(Long productId) {
        productRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("Product", productId));
        return reviewRepository.findByProductId(productId);
    }

    public List<Review> findApprovedByProduct(Long productId) {
        productRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("Product", productId));
        return reviewRepository.findByProductIdAndApproved(productId, true);
    }

    public List<Review> findByCustomer(Long customerId) {
        customerService.findById(customerId); // validate exists
        return reviewRepository.findByCustomerId(customerId);
    }

    public double calculateAverageRating(Long productId) {
        productRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("Product", productId));
        return reviewRepository.findAverageRatingByProductId(productId);
    }
}
