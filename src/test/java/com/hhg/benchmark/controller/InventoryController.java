package com.hhg.benchmark.controller;

import com.hhg.benchmark.entity.Product;
import com.hhg.benchmark.exception.NotFoundException;
import com.hhg.benchmark.exception.ValidationException;
import com.hhg.benchmark.service.InventoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/low-stock")
    public ResponseEntity<List<Product>> findLowStock(@RequestParam int threshold) {
        return ResponseEntity.ok(inventoryService.findLowStock(threshold));
    }

    @GetMapping("/product/{productId}")
    public ResponseEntity<?> getStockLevel(@PathVariable Long productId) {
        try {
            return ResponseEntity.ok(inventoryService.getStockLevel(productId));
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/snapshot")
    public ResponseEntity<Map<Long, Integer>> getInventorySnapshot() {
        return ResponseEntity.ok(inventoryService.getInventorySnapshot());
    }

    @PostMapping("/product/{productId}/restock")
    public ResponseEntity<?> restock(@PathVariable Long productId, @RequestBody Map<String, Object> body) {
        try {
            int quantity = Integer.parseInt(body.get("quantity").toString());
            inventoryService.restock(productId, quantity);
            return ResponseEntity.ok().build();
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ValidationException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/product/{productId}/reduce")
    public ResponseEntity<?> reduce(@PathVariable Long productId, @RequestBody Map<String, Object> body) {
        try {
            int quantity = Integer.parseInt(body.get("quantity").toString());
            inventoryService.reduce(productId, quantity);
            return ResponseEntity.ok().build();
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ValidationException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
