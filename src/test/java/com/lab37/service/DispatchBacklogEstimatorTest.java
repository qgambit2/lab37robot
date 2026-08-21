package com.lab37.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.lab37.model.Order;
import com.lab37.model.OrderStatus;
import com.lab37.repository.ItemHistoryRepository;
import com.lab37.repository.OrderHistoryRepository;
import com.lab37.repository.OrderItemRepository;
import com.lab37.repository.OrderRepository;
import com.lab37.service.ApiPollResponse.ApiPollItem;

class DispatchBacklogEstimatorTest {

	private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	// 2/min drain rate over a 30-minute horizon → 60 orders drainable
	private static final DispatchProperties PROPERTIES =
			new DispatchProperties(Map.of(), Duration.ofMinutes(30), 2);

	OrderRepository orderRepository = mock(OrderRepository.class);

	private DispatchBacklogEstimator estimatorWithBacklog(long backlog) {
		when(orderRepository.countDispatchable(NOW, NOW.minus(Duration.ofMinutes(30))))
				.thenReturn(backlog);
		return new DispatchBacklogEstimator(orderRepository, PROPERTIES, CLOCK);
	}

	@Test
	void overloadedExactlyWhenBacklogReachesWhatTheRobotCanDrainInTheHorizon() {
		assertThat(estimatorWithBacklog(59).isOverloaded()).isFalse();
		assertThat(estimatorWithBacklog(60).isOverloaded()).isTrue();
	}

	@Test
	void overloadedWebhookOrderIsSavedDroppedNotCreated() {
		DispatchBacklogEstimator overloaded = mock(DispatchBacklogEstimator.class);
		when(overloaded.isOverloaded()).thenReturn(true);
		WebhookOrderProcessor processor = new WebhookOrderProcessor(orderRepository,
				mock(OrderItemRepository.class),
				mock(HistoryRecorder.class), new OrderLengthValidator(), overloaded, CLOCK);
		when(orderRepository.findByExternalOrderIdForUpdate(any())).thenReturn(Optional.empty());

		processor.process(new WebhookOrder(UUID.randomUUID(), "Overeats", "Bread Pitt",
				"Kate", "Bishop", new BigDecimal("10.00"), List.of("Espresso"), "", null));

		ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
		org.mockito.Mockito.verify(orderRepository).save(captor.capture());
		assertThat(captor.getValue().getOrderStatus()).isEqualTo(OrderStatus.DROPPED);
		assertThat(captor.getValue().getError()).isEqualTo("system overload");
	}

	@Test
	void overloadedApiOrderIsSavedDroppedNotCreated() {
		DispatchBacklogEstimator overloaded = mock(DispatchBacklogEstimator.class);
		when(overloaded.isOverloaded()).thenReturn(true);
		OrderItemRepository orderItemRepository = mock(OrderItemRepository.class);
		ApiOrderProcessor processor = new ApiOrderProcessor(orderRepository, orderItemRepository,
				new HistoryRecorder(mock(OrderHistoryRepository.class),
						mock(ItemHistoryRepository.class), orderItemRepository),
				new OrderLengthValidator(), overloaded, CLOCK);
		when(orderRepository.findByExternalOrderIdForUpdate(any())).thenReturn(Optional.empty());
		when(orderItemRepository.findByExternalItemId(any())).thenReturn(Optional.empty());

		processor.process("4645", Map.of("a1".repeat(32),
				new ApiPollItem(4645, "Tomato soup", "Lunch & Dinner", new BigDecimal("5.50"), "ordered")));

		ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
		org.mockito.Mockito.verify(orderRepository).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getOrderStatus()).isEqualTo(OrderStatus.DROPPED);
		assertThat(captor.getValue().getError()).isEqualTo("system overload");
	}
}
