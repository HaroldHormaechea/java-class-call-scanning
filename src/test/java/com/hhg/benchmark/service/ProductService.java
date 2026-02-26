package com.hhg.benchmark.service;

import com.hhg.benchmark.entity.Category;
import com.hhg.benchmark.entity.Product;
import com.hhg.benchmark.entity.Supplier;
import com.hhg.benchmark.exception.NotFoundException;
import com.hhg.benchmark.exception.ValidationException;
import com.hhg.benchmark.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryService categoryService;
    private final SupplierService supplierService;

    public ProductService(ProductRepository productRepository,
                          CategoryService categoryService,
                          SupplierService supplierService) {
        this.productRepository = productRepository;
        this.categoryService = categoryService;
        this.supplierService = supplierService;
    }

    @Transactional
    public Product createProduct(String name, String description, BigDecimal price,
                                 int stock, Long categoryId, Long supplierId) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("Product name must not be blank");
        }
        if (price == null) {
            throw new ValidationException("Price must not be null");
        }
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Price must be greater than zero");
        }
        Category category = categoryService.findById(categoryId);
        Supplier supplier = supplierService.findById(supplierId);

        Product product = new Product();
        product.setName(name);
        product.setDescription(description);
        product.setPrice(price);
        product.setStockQuantity(stock);
        product.setCategory(category);
        product.setSupplier(supplier);
        product.setActive(true);
        product.setCreatedAt(LocalDateTime.now());
        return productRepository.save(product);
    }

    @Transactional
    public Product updateProduct(Long id, String name, String description, BigDecimal price) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product", id));
        if (name != null && !name.isBlank()) {
            product.setName(name);
        }
        if (description != null) {
            product.setDescription(description);
        }
        if (price != null) {
            if (price.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ValidationException("Price must be greater than zero");
            }
            product.setPrice(price);
        }
        return productRepository.save(product);
    }

    @Transactional
    public void deleteProduct(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product", id));
        product.setActive(false);
        productRepository.save(product);
    }

    public Product findById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product", id));
    }

    public List<Product> findAll() {
        return productRepository.findAll();
    }

    public List<Product> findByCategory(Long categoryId) {
        Category category = categoryService.findById(categoryId);
        return productRepository.findByCategory(category);
    }

    public List<Product> searchByName(String name) {
        return productRepository.findByNameContainingIgnoreCase(name);
    }

    @Transactional
    public Product updateStock(Long id, int delta) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product", id));
        int newQty = product.getStockQuantity() + delta;
        if (newQty < 0) {
            throw new ValidationException("Insufficient stock");
        }
        product.setStockQuantity(newQty);
        return productRepository.save(product);
    }

    public List<Product> findLowStock(int threshold) {
        return productRepository.findByStockQuantityLessThan(threshold);
    }
}
