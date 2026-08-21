package com.lab37.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.lab37.model.OrderItem;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

	List<OrderItem> findByOrderId(UUID orderId);

	Optional<OrderItem> findByExternalItemId(String externalItemId);

	void deleteByOrderId(UUID orderId);
}
