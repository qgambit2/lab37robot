package com.lab37.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.lab37.model.ItemHistory;

public interface ItemHistoryRepository extends JpaRepository<ItemHistory, UUID> {

	List<ItemHistory> findByOrderIdOrderByOrderVersion(UUID orderId);
}
