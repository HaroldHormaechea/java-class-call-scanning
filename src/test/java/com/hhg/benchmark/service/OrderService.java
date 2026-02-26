package com.hhg.benchmark.service;

import com.hhg.benchmark.entity.Customer;
import com.hhg.benchmark.entity.CustomerOrder;
import com.hhg.benchmark.entity.OrderItem;
import com.hhg.benchmark.entity.Product;
import com.hhg.benchmark.exception.NotFoundException;
import com.hhg.benchmark.exception.ValidationException;
import com.hhg.benchmark.repository.OrderItemRepository;
import com.hhg.benchmark.repository.OrderRepository;
import com.hhg.benchmark.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CustomerService customerService;
    private final ProductRepository productRepository;
    private final InventoryService inventoryService;

    public OrderService(OrderRepository orderRepository,
                        OrderItemRepository orderItemRepository,
                        CustomerService customerService,
                        ProductRepository productRepository,
                        InventoryService inventoryService) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.customerService = customerService;
        this.productRepository = productRepository;
        this.inventoryService = inventoryService;
    }

    @Transactional
    public CustomerOrder createOrder(Long customerId, Map<Long, Integer> productQuantities, String notes) {
        Customer customer = customerService.findById(customerId);
        if (productQuantities == null || productQuantities.isEmpty()) {
            throw new ValidationException("Order must contain at least one product");
        }

        CustomerOrder order = new CustomerOrder();
        order.setCustomer(customer);
        order.setOrderDate(LocalDateTime.now());
        order.setStatus("PENDING");
        order.setNotes(notes);
        order.setTotal(BigDecimal.ZERO);
        // Save first to get an ID for OrderItems
        orderRepository.save(order);

        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Long, Integer> entry : productQuantities.entrySet()) {
            Long productId = entry.getKey();
            int qty = entry.getValue();
            Product product = productRepository.findById(productId)
                    .orElseThrow(() -> new NotFoundException("Product", productId));
            inventoryService.reduce(product.getId(), qty);
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProduct(product);
            item.setQuantity(qty);
            item.setUnitPrice(product.getPrice());
            orderItemRepository.save(item);
            total = total.add(product.getPrice().multiply(BigDecimal.valueOf(qty)));
        }

        order.setTotal(total);
        return orderRepository.save(order);
    }

    @Transactional
    public CustomerOrder cancelOrder(Long id) {
        CustomerOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("CustomerOrder", id));
        if ("DELIVERED".equals(order.getStatus())) {
            throw new ValidationException("Cannot cancel delivered order");
        }
        List<OrderItem> items = orderItemRepository.findByOrderId(id);
        for (OrderItem item : items) {
            inventoryService.restock(item.getProduct().getId(), item.getQuantity());
        }
        order.setStatus("CANCELLED");
        return orderRepository.save(order);
    }

    @Transactional
    public CustomerOrder confirmOrder(Long id) {
        CustomerOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("CustomerOrder", id));
        if (!"PENDING".equals(order.getStatus())) {
            throw new ValidationException("Order must be in PENDING status to confirm");
        }
        order.setStatus("CONFIRMED");
        return orderRepository.save(order);
    }

    @Transactional
    public CustomerOrder shipOrder(Long id) {
        CustomerOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("CustomerOrder", id));
        if (!"CONFIRMED".equals(order.getStatus())) {
            throw new ValidationException("Order must be in CONFIRMED status to ship");
        }
        order.setStatus("SHIPPED");
        return orderRepository.save(order);
    }

    @Transactional
    public CustomerOrder deliverOrder(Long id) {
        CustomerOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("CustomerOrder", id));
        if (!"SHIPPED".equals(order.getStatus())) {
            throw new ValidationException("Order must be in SHIPPED status to deliver");
        }
        order.setStatus("DELIVERED");
        return orderRepository.save(order);
    }

    public CustomerOrder findById(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("CustomerOrder", id));
    }

    public List<CustomerOrder> findByCustomer(Long customerId) {
        Customer customer = customerService.findById(customerId);
        return orderRepository.findByCustomer(customer);
    }

    public List<CustomerOrder> findByStatus(String status) {
        return orderRepository.findByStatus(status);
    }

    public BigDecimal calculateTotal(Long orderId) {
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        return items.stream()
                .map(i -> i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
