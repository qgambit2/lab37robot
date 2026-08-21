package com.lab37.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.lab37.model.Order;
import com.lab37.model.OrderItem;
import com.lab37.model.OrderStatus;
import com.lab37.repository.OrderItemRepository;
import com.lab37.repository.OrderRepository;
import com.lab37.service.ApiPollResponse.ApiPollItem;

/**
 * Applies one external order's item deltas from a polling-API response.
 * Runs in a single transaction per order, reading the order row through a
 * pessimistic lock (with the @Version optimistic lock as backstop) so the
 * robot dispatcher can't pick up the order mid-update. Idempotent: replaying
 * the same delta changes nothing and appends no history.
 */
@Component
public class ApiOrderProcessor {

	private static final Logger log = LoggerFactory.getLogger(ApiOrderProcessor.class);

	private static final String SOURCE_STATUS_ORDERED = "ordered";
	private static final String SOURCE_STATUS_CANCELLED = "cancelled";

	private final OrderRepository orderRepository;
	private final OrderItemRepository orderItemRepository;
	private final HistoryRecorder historyRecorder;
	private final OrderLengthValidator orderLengthValidator;
	private final DispatchBacklogEstimator backlogEstimator;
	private final Clock clock;

	public ApiOrderProcessor(OrderRepository orderRepository,
			OrderItemRepository orderItemRepository,
			HistoryRecorder historyRecorder,
			OrderLengthValidator orderLengthValidator,
			DispatchBacklogEstimator backlogEstimator,
			Clock clock) {
		this.orderRepository = orderRepository;
		this.orderItemRepository = orderItemRepository;
		this.historyRecorder = historyRecorder;
		this.orderLengthValidator = orderLengthValidator;
		this.backlogEstimator = backlogEstimator;
		this.clock = clock;
	}

	/**
	 * @param externalOrderId the source's numeric order id, as a string
	 * @param items this order's delta entries, keyed by external item id
	 */
	@Transactional
	public void process(String externalOrderId, Map<String, ApiPollItem> items) {
		Order order = orderRepository.findByExternalOrderIdForUpdate(externalOrderId).orElse(null);
		boolean changed = false;
		if (order == null) {
			// not saved yet: persisted once below, after items/amount are
			// computed, so a new order inserts at version 1 (no extra bump)
			order = Order.fromApiPull(externalOrderId);
			changed = true;
			// a new order arriving into a backlog the robot can't drain in
			// time would expire in the queue — saved as DROPPED up front
			if (backlogEstimator.isOverloaded()) {
				order.setOrderStatus(OrderStatus.DROPPED);
				order.setError(DispatchBacklogEstimator.OVERLOAD_ERROR);
				log.warn("API order {} DROPPED — {}", externalOrderId,
						DispatchBacklogEstimator.OVERLOAD_ERROR);
			}
		}
		for (Map.Entry<String, ApiPollItem> entry : items.entrySet()) {
			changed |= upsertItem(order, entry.getKey(), entry.getValue());
		}
		if (changed) {
			List<OrderItem> current = orderItemRepository.findByOrderId(order.getId());
			List<OrderItem> active = current.stream()
					.filter(item -> !SOURCE_STATUS_CANCELLED.equalsIgnoreCase(item.getSourceStatus()))
					.toList();
			List<String> activeNames = active.stream()
					.map(OrderItem::getItemName)
					.toList();
			order.setItems(String.join(", ", activeNames));
			order.setAmount(active.stream()
					.map(OrderItem::getItemPrice)
					.filter(price -> price != null)
					.reduce(BigDecimal.ZERO, BigDecimal::add));
			order.setUpdatedAt(Instant.now(clock));
			// deltas grow the item list over time, so re-check the length
			// limits on every change while the order is still dispatchable.
			// The delta's raw names are checked too: stored names are already
			// truncated to the column size, which would mask a violation
			List<String> deltaNames = items.values().stream()
					.map(ApiPollItem::name)
					.filter(name -> name != null)
					.toList();
			if (order.getOrderStatus() == OrderStatus.CREATED
					&& (orderLengthValidator.exceedsLimits(activeNames)
							|| orderLengthValidator.exceedsLimits(deltaNames))) {
				order.setOrderStatus(OrderStatus.ERROR);
				order.setError(OrderLengthValidator.ERROR_MESSAGE);
				log.warn("API order {} rejected — {}", order.getExternalOrderId(),
						OrderLengthValidator.ERROR_MESSAGE);
			}
			// flush so Hibernate's @Version increment lands before we snapshot
			orderRepository.saveAndFlush(order);
			historyRecorder.snapshot(order);
		}
	}

	private boolean upsertItem(Order order, String externalItemId, ApiPollItem item) {
		OrderItem existing = orderItemRepository.findByExternalItemId(externalItemId).orElse(null);
		if (existing == null) {
			orderItemRepository.save(OrderItem.fromApi(order.getId(), item.name(), item.price(),
					externalItemId, item.status()));
			return true;
		}
		if (item.status() != null && item.status().equalsIgnoreCase(existing.getSourceStatus())) {
			return false; // replayed delta — nothing new
		}
		// a cancellation is honored only while the item is still "ordered"
		// (or never source-statused) — once the source is processing it,
		// it's too late to cancel and all we can do is log the failure
		if (SOURCE_STATUS_CANCELLED.equalsIgnoreCase(item.status())
				&& existing.getSourceStatus() != null
				&& !SOURCE_STATUS_ORDERED.equalsIgnoreCase(existing.getSourceStatus())) {
			log.warn("Item {} of order {} cannot be cancelled — source status is already '{}'",
					externalItemId, order.getExternalOrderId(), existing.getSourceStatus());
			return false;
		}
		existing.setSourceStatus(item.status());
		orderItemRepository.save(existing);
		return true;
	}
}
