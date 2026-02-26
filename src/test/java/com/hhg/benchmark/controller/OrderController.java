package com.hhg.benchmark.controller;

import com.hhg.benchmark.entity.CustomerOrder;
import com.hhg.benchmark.exception.NotFoundException;
import com.hhg.benchmark.exception.ValidationException;
import com.hhg.benchmark.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public ResponseEntity<List<CustomerOrder>> findAllPending() {
        return ResponseEntity.ok(orderService.findByStatus("PENDING"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> findById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(orderService.findById(id));
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<?> findByCustomer(@PathVariable Long customerId) {
        try {
            return ResponseEntity.ok(orderService.findByCustomer(customerId));
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/status")
    public ResponseEntity<List<CustomerOrder>> findByStatus(@RequestParam String status) {
        return ResponseEntity.ok(orderService.findByStatus(status));
    }

    @GetMapping("/{id}/total")
    public ResponseEntity<?> calculateTotal(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(orderService.calculateTotal(id));
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    public ResponseEntity<?> createOrder(@RequestBody Map<String, Object> body) {
        try {
            Long customerId = Long.valueOf(body.get("customerId").toString());
            String notes = (String) body.getOrDefault("notes", "");
            @SuppressWarnings("unchecked")
            Map<String, Integer> rawQty = (Map<String, Integer>) body.get("productQuantities");
            Map<Long, Integer> productQuantities = new HashMap<>();
            if (rawQty != null) {
                rawQty.forEach((k, v) -> productQuantities.put(Long.valueOf(k), v));
            }
            CustomerOrder order = orderService.createOrder(customerId, productQuantities, notes);
            return ResponseEntity.ok(order);
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ValidationException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<?> confirmOrder(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(orderService.confirmOrder(id));
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ValidationException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/{id}/ship")
    public ResponseEntity<?> shipOrder(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(orderService.shipOrder(id));
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ValidationException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/{id}/deliver")
    public ResponseEntity<?> deliverOrder(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(orderService.deliverOrder(id));
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ValidationException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> cancelOrder(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(orderService.cancelOrder(id));
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ValidationException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
