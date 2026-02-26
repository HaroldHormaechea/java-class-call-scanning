package com.hhg.benchmark.repository;

import com.hhg.benchmark.entity.CustomerOrder;
import com.hhg.benchmark.entity.OrderItem;
import com.hhg.benchmark.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrder(CustomerOrder order);

    List<OrderItem> findByOrderId(Long orderId);

    List<OrderItem> findByProduct(Product product);
}
