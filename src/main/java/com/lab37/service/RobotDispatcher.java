package com.lab37.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lab37.model.ItemStatus;
import com.lab37.model.Order;
import com.lab37.model.OrderItem;
import com.lab37.model.OrderStatus;
import com.lab37.repository.OrderItemRepository;
import com.lab37.repository.OrderRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Hands an order to the robot for assembly. Only CREATED orders may be
 * dispatched; cancelled items are excluded from the robot request. For now
 * the robot integration is a skeleton: the request payload is generated and
 * logged, and the order is marked DISPATCHED.
 */
@Service
public class RobotDispatcher {

	private static final Logger log = LoggerFactory.getLogger(RobotDispatcher.class);

	private final OrderRepository orderRepository;
	private final OrderItemRepository orderItemRepository;
	private final HistoryRecorder historyRecorder;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public RobotDispatcher(OrderRepository orderRepository,
			OrderItemRepository orderItemRepository,
			HistoryRecorder historyRecorder,
			ObjectMapper objectMapper,
			Clock clock) {
		this.orderRepository = orderRepository;
		this.orderItemRepository = orderItemRepository;
		this.historyRecorder = historyRecorder;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	/**
	 * Dispatches the order to the robot: builds the robot request from the
	 * order's non-cancelled items, logs it, and transitions the order
	 * CREATED → DISPATCHED (stamping dispatch_time, bumping version, and
	 * appending an order_history snapshot).
	 *
	 * <p>An empty request is never sent to the robot: an order with nothing
	 * left to make is closed instead (CANCELLED when the source cancelled
	 * every item, UNFULFILLED otherwise) and null is returned — the caller
	 * must not count it against the robot rate limit.
	 *
	 * @throws IllegalArgumentException if no such order exists
	 * @throws IllegalStateException if the order is not in CREATED state
	 */
	@Transactional
	public RobotDispatchRequest dispatch(UUID orderId) {
		// row-locked read: serializes with the webhook updater, so an order
		// mid-update can't be dispatched (and vice versa)
		Order order = orderRepository.findByIdForUpdate(orderId)
				.orElseThrow(() -> new IllegalArgumentException("No order with id " + orderId));
		if (order.getOrderStatus() != OrderStatus.CREATED) {
			throw new IllegalStateException("Order " + orderId + " cannot be dispatched: status is "
					+ order.getOrderStatus() + ", must be " + OrderStatus.CREATED);
		}
		// only items the robot should make: from a pipeline with no source
		// status (CSV/webhook) or still "ordered" in the polling API —
		// cancelled items must not be made, and anything further along is
		// already being handled by the source
		List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
		List<OrderItem> toSend = items.stream()
				.filter(item -> item.getSourceStatus() == null
						|| "ordered".equalsIgnoreCase(item.getSourceStatus()))
				.toList();
		if (toSend.isEmpty()) {
			return closeWithoutDispatch(order, items);
		}
		RobotDispatchRequest request = new RobotDispatchRequest(orderId,
				toSend.stream()
						.map(item -> new RobotDispatchRequest.Item(item.getId(), item.getItemName()))
						.toList());
		log.info("Dispatching order to robot: {}", objectMapper.writeValueAsString(request));

		Instant now = Instant.now(clock);
		// flip the sent items to DISPATCHED first, so the history snapshot
		// below records exactly which items this dispatch included (the
		// source keeps advancing source_status afterwards; this flag is ours)
		for (OrderItem item : toSend) {
			item.setStatus(ItemStatus.DISPATCHED);
		}
		orderItemRepository.saveAll(toSend);
		// not every item made it into the payload → PARTIALLY_DISPATCHED, so
		// partially-made orders are directly searchable
		order.setOrderStatus(toSend.size() == items.size()
				? OrderStatus.DISPATCHED
				: OrderStatus.PARTIALLY_DISPATCHED);
		order.setDispatchTime(now);
		order.setUpdatedAt(now);
		// flush so Hibernate's @Version increment lands before we snapshot
		orderRepository.saveAndFlush(order);
		historyRecorder.snapshot(order);
		return request;
	}

	/**
	 * Ends the lifecycle of an order with nothing for the robot to make.
	 * Every item cancelled by the source → CANCELLED; otherwise (all
	 * remaining items already being handled by the source, or an order that
	 * has no items at all) → UNFULFILLED with the reason recorded in the
	 * order's error column. Returns null: no robot request was sent.
	 */
	private RobotDispatchRequest closeWithoutDispatch(Order order, List<OrderItem> items) {
		if (!items.isEmpty()
				&& items.stream().allMatch(
						item -> "cancelled".equalsIgnoreCase(item.getSourceStatus()))) {
			order.setOrderStatus(OrderStatus.CANCELLED);
			log.info("Order {} not dispatched — every item was cancelled by the source", order.getId());
		} else {
			order.setOrderStatus(OrderStatus.UNFULFILLED);
			order.setError(items.isEmpty()
					? "order has no items"
					: "all items already handled by the source at dispatch time");
			log.warn("Order {} UNFULFILLED instead of dispatched — {}", order.getId(), order.getError());
		}
		order.setUpdatedAt(Instant.now(clock));
		// flush so Hibernate's @Version increment lands before we snapshot
		orderRepository.saveAndFlush(order);
		historyRecorder.snapshot(order);
		return null;
	}
}
