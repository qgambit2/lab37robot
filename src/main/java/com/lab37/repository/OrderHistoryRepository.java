package com.lab37.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.lab37.model.OrderHistory;

public interface OrderHistoryRepository extends JpaRepository<OrderHistory, UUID> {

	List<OrderHistory> findByOrderIdOrderByVersion(UUID orderId);
}
