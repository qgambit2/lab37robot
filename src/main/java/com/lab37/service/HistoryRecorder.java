package com.lab37.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.lab37.model.ItemHistory;
import com.lab37.model.Order;
import com.lab37.model.OrderHistory;
import com.lab37.repository.ItemHistoryRepository;
import com.lab37.repository.OrderHistoryRepository;
import com.lab37.repository.OrderItemRepository;

/**
 * The single place an order history snapshot is written: one order_history
 * row plus one item_history row per current order item, all under the
 * order's version — so every history entry knows exactly which items the
 * order had, and in what state, at that step. Callers must invoke this
 * AFTER the order (flushed, so the @Version bump has landed) and its item
 * rows are persisted, inside the same transaction.
 */
@Component
public class HistoryRecorder {

	private final OrderHistoryRepository orderHistoryRepository;
	private final ItemHistoryRepository itemHistoryRepository;
	private final OrderItemRepository orderItemRepository;

	public HistoryRecorder(OrderHistoryRepository orderHistoryRepository,
			ItemHistoryRepository itemHistoryRepository,
			OrderItemRepository orderItemRepository) {
		this.orderHistoryRepository = orderHistoryRepository;
		this.itemHistoryRepository = itemHistoryRepository;
		this.orderItemRepository = orderItemRepository;
	}

	public void snapshot(Order order) {
		orderHistoryRepository.save(OrderHistory.of(order));
		List<ItemHistory> items = orderItemRepository.findByOrderId(order.getId()).stream()
				.map(item -> ItemHistory.of(item, order.getVersion()))
				.toList();
		itemHistoryRepository.saveAll(items);
	}
}
