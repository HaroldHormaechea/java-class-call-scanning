package com.hhg.benchmark.controller;

import com.hhg.benchmark.entity.Invoice;
import com.hhg.benchmark.exception.NotFoundException;
import com.hhg.benchmark.exception.ValidationException;
import com.hhg.benchmark.service.InvoiceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @GetMapping
    public ResponseEntity<List<Invoice>> findOverdue() {
        return ResponseEntity.ok(invoiceService.findOverdue());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> findById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(invoiceService.findById(id));
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<Invoice>> findByCustomer(@PathVariable Long customerId) {
        return ResponseEntity.ok(invoiceService.findByCustomer(customerId));
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<?> findByOrder(@PathVariable Long orderId) {
        try {
            return ResponseEntity.ok(invoiceService.findByOrder(orderId));
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/order/{orderId}")
    public ResponseEntity<?> generate(@PathVariable Long orderId) {
        try {
            return ResponseEntity.ok(invoiceService.generate(orderId));
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ValidationException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/{id}/pay")
    public ResponseEntity<?> markPaid(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(invoiceService.markPaid(id));
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ValidationException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/{id}/overdue")
    public ResponseEntity<?> markOverdue(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(invoiceService.markOverdue(id));
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ValidationException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
