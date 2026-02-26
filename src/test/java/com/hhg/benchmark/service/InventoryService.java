package com.hhg.benchmark.service;

import com.hhg.benchmark.entity.Product;
import com.hhg.benchmark.exception.NotFoundException;
import com.hhg.benchmark.exception.ValidationException;
import com.hhg.benchmark.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class InventoryService {

    private final ProductRepository productRepository;
    private final Map<Long, Integer> inventoryMap = new HashMap<>();

    public InventoryService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public int getStockLevel(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("Product", productId));
        return product.getStockQuantity() + inventoryMap.getOrDefault(productId, 0);
    }

    @Transactional
    public void restock(Long productId, int quantity) {
        if (quantity <= 0) {
            throw new ValidationException("Restock quantity must be greater than zero");
        }
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("Product", productId));
        product.setStockQuantity(product.getStockQuantity() + quantity);
        productRepository.save(product);
        inventoryMap.merge(productId, quantity, Integer::sum);
    }

    @Transactional
    public void reduce(Long productId, int quantity) {
        if (quantity <= 0) {
            throw new ValidationException("Reduce quantity must be greater than zero");
        }
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("Product", productId));
        if (product.getStockQuantity() < quantity) {
            throw new ValidationException("Insufficient stock for product id: " + productId);
        }
        product.setStockQuantity(product.getStockQuantity() - quantity);
        productRepository.save(product);
        inventoryMap.merge(productId, -quantity, Integer::sum);
    }

    public List<Product> findLowStock(int threshold) {
        return productRepository.findByStockQuantityLessThan(threshold);
    }

    public Map<Long, Integer> getInventorySnapshot() {
        return Collections.unmodifiableMap(inventoryMap);
    }
}
