package com.lab37.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.lab37.model.Order;
import com.lab37.model.OrderItem;
import com.lab37.model.OrderStatus;
import com.lab37.model.OrderType;
import com.lab37.repository.OrderHistoryRepository;
import com.lab37.repository.OrderItemRepository;
import com.lab37.repository.OrderRepository;
import com.lab37.service.ApiPollResponse.ApiPollItem;

// same property set as OrderProcessorTest so the Spring context is reused
@SpringBootTest(properties = {
		"ingest.poll-interval=1h",
		"ingest.batch-size=2",
		"dispatch.poll-interval=1h",
		"dispatch.sweep-interval=1h",
		"webhook.poll-interval=1h",
		"polling.poll-interval=1h",
		"polling.queue-poll-interval=1h"})
class ApiPollIntegrationTest {

	private static final String ITEM_SOUP = "a1".repeat(32); // 64-char ids like the real ones
	private static final String ITEM_PIE = "b2".repeat(32);

	@Autowired
	ApiOrderProcessor apiOrderProcessor;

	@Autowired
	RobotDispatcher robotDispatcher;

	@Autowired
	OrderRepository orderRepository;

	@Autowired
	OrderItemRepository orderItemRepository;

	@Autowired
	OrderHistoryRepository orderHistoryRepository;

	@BeforeEach
	void cleanState() {
		orderHistoryRepository.deleteAll();
		orderItemRepository.deleteAll();
		orderRepository.deleteAll();
	}

	private ApiPollItem item(long order, String name, String price, String status) {
		return new ApiPollItem(order, name, "Lunch & Dinner", new BigDecimal(price), status);
	}

	@Test
	void deltasCreateUpdateAndCancelThenDispatchOnlyOrderedItems() {
		// delta 1: unseen order 4645 with two ordered items → order created
		apiOrderProcessor.process("4645", Map.of(
				ITEM_SOUP, item(4645, "Tomato soup", "5.50", "ordered"),
				ITEM_PIE, item(4645, "Apple pie", "7.25", "ordered")));

		Order order = orderRepository.findByExternalOrderId("4645").orElseThrow();
		assertThat(order.getOrderType()).isEqualTo(OrderType.API_PULL);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CREATED);
		assertThat(order.getAmount()).isEqualByComparingTo("12.75"); // summed item prices
		assertThat(order.getVersion()).isEqualTo(1);
		List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
		assertThat(items).hasSize(2);
		assertThat(items).allSatisfy(i -> assertThat(i.getSourceStatus()).isEqualTo("ordered"));

		// replaying the same delta is a no-op: no version bump, no history spam
		apiOrderProcessor.process("4645", Map.of(
				ITEM_SOUP, item(4645, "Tomato soup", "5.50", "ordered")));
		assertThat(orderRepository.findByExternalOrderId("4645").orElseThrow().getVersion())
				.isEqualTo(1);
		assertThat(orderHistoryRepository.findByOrderIdOrderByVersion(order.getId())).hasSize(1);

		// delta 2: soup progressed to processing; pie cancelled while still ordered
		apiOrderProcessor.process("4645", Map.of(
				ITEM_SOUP, item(4645, "Tomato soup", "5.50", "processing"),
				ITEM_PIE, item(4645, "Apple pie", "7.25", "cancelled")));

		Order updated = orderRepository.findByExternalOrderId("4645").orElseThrow();
		assertThat(updated.getVersion()).isEqualTo(2);
		assertThat(updated.getAmount()).isEqualByComparingTo("5.50"); // cancelled pie excluded
		OrderItem pie = orderItemRepository.findByExternalItemId(ITEM_PIE).orElseThrow();
		assertThat(pie.getSourceStatus()).isEqualTo("cancelled");

		// dispatch: soup is "processing" (already being made at the source) and
		// pie is cancelled — nothing left for the robot, so no request is sent
		// and the order is closed as UNFULFILLED instead
		assertThat(robotDispatcher.dispatch(order.getId())).isNull();
		Order closed = orderRepository.findByExternalOrderId("4645").orElseThrow();
		assertThat(closed.getOrderStatus()).isEqualTo(OrderStatus.UNFULFILLED);
		assertThat(closed.getError()).contains("already handled by the source");
		assertThat(closed.getDispatchTime()).isNull();
	}

	@Test
	void oversizedItemNameInDeltaMarksOrderError() {
		String hugeName = "x".repeat(2000); // over the 1024 item-name limit
		apiOrderProcessor.process("4698", Map.of(
				"c3".repeat(32), item(4698, hugeName, "5.00", "ordered")));

		Order order = orderRepository.findByExternalOrderId("4698").orElseThrow();
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.ERROR);
		assertThat(order.getError()).isEqualTo("order length exceeds maximum limit");
	}

	@Test
	void cancellingAnItemPastOrderedIsRefused() {
		apiOrderProcessor.process("4650", Map.of(
				ITEM_SOUP, item(4650, "Cold brew", "4.00", "with_courier")));

		apiOrderProcessor.process("4650", Map.of(
				ITEM_SOUP, item(4650, "Cold brew", "4.00", "cancelled")));

		OrderItem item = orderItemRepository.findByExternalItemId(ITEM_SOUP).orElseThrow();
		assertThat(item.getSourceStatus()).isEqualTo("with_courier"); // unchanged — not cancelled
	}
}
