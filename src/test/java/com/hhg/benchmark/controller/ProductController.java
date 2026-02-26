package com.hhg.benchmark.controller;

import com.hhg.benchmark.entity.Product;
import com.hhg.benchmark.exception.NotFoundException;
import com.hhg.benchmark.exception.ValidationException;
import com.hhg.benchmark.service.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public ResponseEntity<List<Product>> findAll() {
        return ResponseEntity.ok(productService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> findById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(productService.findById(id));
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/search")
    public ResponseEntity<List<Product>> searchByName(@RequestParam String name) {
        return ResponseEntity.ok(productService.searchByName(name));
    }

    @GetMapping("/category/{categoryId}")
    public ResponseEntity<?> findByCategory(@PathVariable Long categoryId) {
        try {
            return ResponseEntity.ok(productService.findByCategory(categoryId));
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/low-stock")
    public ResponseEntity<List<Product>> findLowStock(@RequestParam int threshold) {
        return ResponseEntity.ok(productService.findLowStock(threshold));
    }

    @PostMapping
    public ResponseEntity<?> createProduct(@RequestBody Map<String, Object> body) {
        try {
            String name = (String) body.get("name");
            String description = (String) body.get("description");
            BigDecimal price = body.get("price") != null
                    ? new BigDecimal(body.get("price").toString()) : null;
            int stockQuantity = body.get("stockQuantity") != null
                    ? Integer.parseInt(body.get("stockQuantity").toString()) : 0;
            Long categoryId = body.get("categoryId") != null
                    ? Long.valueOf(body.get("categoryId").toString()) : null;
            Long supplierId = body.get("supplierId") != null
                    ? Long.valueOf(body.get("supplierId").toString()) : null;
            Product product = productService.createProduct(name, description, price,
                    stockQuantity, categoryId, supplierId);
            return ResponseEntity.ok(product);
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ValidationException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateProduct(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            String name = (String) body.get("name");
            String description = (String) body.get("description");
            BigDecimal price = body.get("price") != null
                    ? new BigDecimal(body.get("price").toString()) : null;
            return ResponseEntity.ok(productService.updateProduct(id, name, description, price));
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ValidationException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/{id}/stock")
    public ResponseEntity<?> updateStock(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            int delta = Integer.parseInt(body.get("delta").toString());
            return ResponseEntity.ok(productService.updateStock(id, delta));
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ValidationException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteProduct(@PathVariable Long id) {
        try {
            productService.deleteProduct(id);
            return ResponseEntity.noContent().build();
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
