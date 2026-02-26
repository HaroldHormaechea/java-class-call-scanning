package com.hhg.benchmark.service;

import com.hhg.benchmark.entity.CustomerOrder;
import com.hhg.benchmark.entity.Invoice;
import com.hhg.benchmark.exception.NotFoundException;
import com.hhg.benchmark.exception.ValidationException;
import com.hhg.benchmark.repository.InvoiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final OrderService orderService;

    public InvoiceService(InvoiceRepository invoiceRepository, OrderService orderService) {
        this.invoiceRepository = invoiceRepository;
        this.orderService = orderService;
    }

    @Transactional
    public Invoice generate(Long orderId) {
        CustomerOrder order = orderService.findById(orderId);
        if (invoiceRepository.findByOrder(order).isPresent()) {
            throw new ValidationException("Invoice already exists for order id: " + orderId);
        }
        if (!"CONFIRMED".equals(order.getStatus()) && !"DELIVERED".equals(order.getStatus())) {
            throw new ValidationException("Order must be confirmed or delivered to generate an invoice");
        }
        Invoice invoice = new Invoice();
        invoice.setOrder(order);
        invoice.setCustomer(order.getCustomer());
        invoice.setInvoiceDate(LocalDateTime.now());
        invoice.setDueDate(LocalDate.now().plusDays(30));
        invoice.setTotal(order.getTotal());
        invoice.setStatus("SENT");
        return invoiceRepository.save(invoice);
    }

    @Transactional
    public Invoice markPaid(Long id) {
        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Invoice", id));
        if ("PAID".equals(invoice.getStatus())) {
            throw new ValidationException("Invoice is already paid");
        }
        invoice.setStatus("PAID");
        return invoiceRepository.save(invoice);
    }

    @Transactional
    public Invoice markOverdue(Long id) {
        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Invoice", id));
        invoice.setStatus("OVERDUE");
        return invoiceRepository.save(invoice);
    }

    public Invoice findById(Long id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Invoice", id));
    }

    public List<Invoice> findByCustomer(Long customerId) {
        return invoiceRepository.findByCustomerId(customerId);
    }

    public Invoice findByOrder(Long orderId) {
        CustomerOrder order = orderService.findById(orderId);
        return invoiceRepository.findByOrder(order)
                .orElseThrow(() -> new NotFoundException("Invoice for order", orderId));
    }

    public List<Invoice> findOverdue() {
        return invoiceRepository.findByStatus("OVERDUE");
    }
}
