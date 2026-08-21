package com.lab37.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lab37.model.Order;
import com.lab37.model.OrderHistory;
import com.lab37.model.OrderItem;
import com.lab37.model.OrderStatus;
import com.lab37.repository.OrderHistoryRepository;
import com.lab37.repository.OrderItemRepository;
import com.lab37.repository.OrderRepository;

import tools.jackson.databind.json.JsonMapper;

class RobotDispatcherTest {

	private static final Instant NOW = Instant.parse("2026-08-19T15:00:00Z");

	OrderRepository orderRepository;
	OrderItemRepository orderItemRepository;
	OrderHistoryRepository orderHistoryRepository;
	RobotDispatcher dispatcher;

	@BeforeEach
	void setUp() {
		orderRepository = mock(OrderRepository.class);
		orderItemRepository = mock(OrderItemRepository.class);
		orderHistoryRepository = mock(OrderHistoryRepository.class);
		dispatcher = new RobotDispatcher(orderRepository, orderItemRepository,
				new HistoryRecorder(orderHistoryRepository,
						mock(com.lab37.repository.ItemHistoryRepository.class), orderItemRepository),
				new JsonMapper(),
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private Order createdOrder() {
		return Order.fromCsv(UUID.randomUUID(), "Alice", "Smith",
				"burger, fries", "none", false, "lunch");
	}

	@Test
	void dispatchTransitionsCreatedOrderAndBuildsRequestFromItems() {
		Order order = createdOrder();
		OrderItem burger = OrderItem.of(order.getId(), "burger", null);
		OrderItem fries = OrderItem.of(order.getId(), "fries", null);
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of(burger, fries));

		RobotDispatchRequest request = dispatcher.dispatch(order.getId());

		assertThat(request.orderId()).isEqualTo(order.getId());
		// each payload item carries our order_items id, so the robot can
		// report back per item
		assertThat(request.items()).containsExactly(
				new RobotDispatchRequest.Item(burger.getId(), "burger"),
				new RobotDispatchRequest.Item(fries.getId(), "fries"));
		// sent items are flipped to DISPATCHED so history records what was sent
		assertThat(burger.getStatus()).isEqualTo(com.lab37.model.ItemStatus.DISPATCHED);
		assertThat(fries.getStatus()).isEqualTo(com.lab37.model.ItemStatus.DISPATCHED);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.DISPATCHED);
		assertThat(order.getDispatchTime()).isEqualTo(NOW);
		assertThat(order.getUpdatedAt()).isEqualTo(NOW);
		// version bump is Hibernate's (@Version, applied at flush) — asserted
		// in the integration tests, invisible under mocked repositories
		verify(orderRepository).saveAndFlush(order);
		verify(orderHistoryRepository).save(any(OrderHistory.class));
	}

	@Test
	void dispatchExcludesCancelledItemsFromRobotRequest() {
		Order order = createdOrder();
		OrderItem cancelled = OrderItem.fromApi(order.getId(), "milkshake", new BigDecimal("5.00"),
				"m1", "cancelled");
		OrderItem burger = OrderItem.of(order.getId(), "burger", null);
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of(
				burger,
				cancelled));

		RobotDispatchRequest request = dispatcher.dispatch(order.getId());

		assertThat(request.items())
				.containsExactly(new RobotDispatchRequest.Item(burger.getId(), "burger"));
		// the sent item is flipped to DISPATCHED; the excluded one stays CREATED
		assertThat(cancelled.getStatus()).isEqualTo(com.lab37.model.ItemStatus.CREATED);
		// not every item made it into the payload → PARTIALLY_DISPATCHED
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PARTIALLY_DISPATCHED);
	}

	@Test
	void allItemsCancelledCancelsOrderInsteadOfDispatchingEmptyRequest() {
		Order order = createdOrder();
		OrderItem cancelled = OrderItem.fromApi(order.getId(), "milkshake", null, "m1", "cancelled");
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of(cancelled));

		assertThat(dispatcher.dispatch(order.getId())).isNull();

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(order.getDispatchTime()).isNull();
		assertThat(order.getUpdatedAt()).isEqualTo(NOW);
		verify(orderRepository).saveAndFlush(order);
		verify(orderHistoryRepository).save(any(OrderHistory.class));
	}

	@Test
	void allItemsHandledBySourceMarksOrderUnfulfilledInsteadOfDispatchingEmptyRequest() {
		Order order = createdOrder();
		OrderItem handled = OrderItem.fromApi(order.getId(), "soup", null, "a1", "processing");
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of(handled));

		assertThat(dispatcher.dispatch(order.getId())).isNull();

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.UNFULFILLED);
		assertThat(order.getError()).contains("already handled by the source");
		assertThat(order.getDispatchTime()).isNull();
		verify(orderRepository).saveAndFlush(order);
		verify(orderHistoryRepository).save(any(OrderHistory.class));
	}

	@Test
	void orderWithoutItemsMarksOrderUnfulfilledInsteadOfDispatchingEmptyRequest() {
		Order order = createdOrder();
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of());

		assertThat(dispatcher.dispatch(order.getId())).isNull();

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.UNFULFILLED);
		assertThat(order.getError()).contains("no items");
	}

	@Test
	void dispatchRejectsOrdersNotInCreatedState() {
		for (OrderStatus status : List.of(OrderStatus.DISPATCHED, OrderStatus.PARTIALLY_DISPATCHED,
				OrderStatus.UNFULFILLED, OrderStatus.CANCELLED, OrderStatus.ERROR,
				OrderStatus.DROPPED)) {
			Order order = createdOrder();
			order.setOrderStatus(status);
			when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

			assertThatThrownBy(() -> dispatcher.dispatch(order.getId()))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining(status.name())
					.hasMessageContaining("CREATED");
		}
		verify(orderRepository, never()).save(any());
		verify(orderHistoryRepository, never()).save(any());
	}

	@Test
	void dispatchRejectsUnknownOrder() {
		UUID unknown = UUID.randomUUID();
		when(orderRepository.findByIdForUpdate(unknown)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> dispatcher.dispatch(unknown))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(unknown.toString());
	}
}
