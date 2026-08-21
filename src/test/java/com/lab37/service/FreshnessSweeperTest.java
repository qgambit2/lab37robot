package com.lab37.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.lab37.model.Order;
import com.lab37.model.OrderHistory;
import com.lab37.model.OrderStatus;
import com.lab37.repository.ItemHistoryRepository;
import com.lab37.repository.OrderHistoryRepository;
import com.lab37.repository.OrderItemRepository;
import com.lab37.repository.OrderRepository;

class FreshnessSweeperTest {

	private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");

	OrderRepository orderRepository = mock(OrderRepository.class);
	OrderHistoryRepository orderHistoryRepository = mock(OrderHistoryRepository.class);
	FreshnessSweeper sweeper = new FreshnessSweeper(orderRepository,
			new HistoryRecorder(orderHistoryRepository,
					mock(ItemHistoryRepository.class), mock(OrderItemRepository.class)),
			new DispatchProperties(Map.of(), Duration.ofMinutes(30), 100),
			Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void marksEveryStaleOrderUnfulfilledWithReasonAndSnapshot() {
		Order pastWindow = Order.fromCsv(
				UUID.randomUUID(), "Alice", "Smith", "burger", "", false, "lunch");
		Order agedImmediate = Order.fromWebhook("stale-1", "Overeats", "Bread Pitt",
				"Kate", "Bishop", null, "Espresso", "");
		when(orderRepository.findStaleForUpdate(NOW, NOW.minus(Duration.ofMinutes(30))))
				.thenReturn(List.of(pastWindow, agedImmediate));

		sweeper.sweepStaleOrders();

		for (Order order : List.of(pastWindow, agedImmediate)) {
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.UNFULFILLED);
			assertThat(order.getError()).isEqualTo("not dispatched in time");
			assertThat(order.getUpdatedAt()).isEqualTo(NOW);
		}
		verify(orderRepository, times(2)).saveAndFlush(any(Order.class));
		verify(orderHistoryRepository, times(2)).save(any(OrderHistory.class));
	}

	@Test
	void quietWhenNothingIsStale() {
		when(orderRepository.findStaleForUpdate(any(), any())).thenReturn(List.of());

		sweeper.sweepStaleOrders();

		verify(orderRepository, never()).saveAndFlush(any());
		verify(orderHistoryRepository, never()).save(any());
	}
}
