package com.lab37.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.lab37.model.Order;
import com.lab37.model.OrderItem;
import com.lab37.model.OrderStatus;
import com.lab37.repository.OrderItemRepository;
import com.lab37.repository.OrderRepository;

/**
 * Converts one parsed webhook payload into order rows. All inserts (order,
 * its items, its history snapshot) happen in a single transaction, so a
 * failure can never leave a partial order behind. Idempotent on the webhook
 * order_id (stored as external_order_id): duplicate deliveries are no-ops.
 */
@Component
public class WebhookOrderProcessor {

	private static final Logger log = LoggerFactory.getLogger(WebhookOrderProcessor.class);

	private final OrderRepository orderRepository;
	private final OrderItemRepository orderItemRepository;
	private final HistoryRecorder historyRecorder;
	private final OrderLengthValidator orderLengthValidator;
	private final DispatchBacklogEstimator backlogEstimator;
	private final Clock clock;

	public WebhookOrderProcessor(OrderRepository orderRepository,
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

	@Transactional
	public void process(WebhookOrder payload) {
		// row-locked read (FOR UPDATE): while we hold the lock the dispatcher
		// can't pick this order up, and if the dispatcher got there first we
		// see the committed DISPATCHED state after it releases
		Order existing = orderRepository.findByExternalOrderIdForUpdate(payload.orderId().toString()).orElse(null);
		if (existing != null) {
			if (existing.getOrderStatus() == OrderStatus.DISPATCHED
					|| existing.getOrderStatus() == OrderStatus.PARTIALLY_DISPATCHED) {
				// one rule for updates and cancellations alike: a dispatched
				// order can no longer be changed — the robot is already making it
				log.warn("Webhook {} for order {} (external id {}) ignored — order is already {}",
						payload.isCancellation() ? "cancellation" : "update",
						existing.getId(), payload.orderId(), existing.getOrderStatus());
				return;
			}
			if (payload.isCancellation()) {
				cancel(existing);
			} else {
				update(existing, payload);
			}
			return;
		}
		if (payload.isCancellation()) {
			log.warn("Webhook cancellation for unknown order {} — nothing to cancel", payload.orderId());
			return;
		}
		List<String> items = itemsOf(payload);
		Order order = Order.fromWebhook(payload.orderId().toString(), payload.orderSource(),
				payload.restaurant(), payload.firstName(), payload.lastName(),
				payload.total(), String.join(", ", items), payload.notes());
		rejectIfOversized(order, items);
		dropIfOverloaded(order);
		orderRepository.save(order);
		saveItems(order, items);
		historyRecorder.snapshot(order);
	}

	/**
	 * Oversized orders (combined or single-item length over the limits) are
	 * saved for visibility but marked ERROR so they are never dispatched —
	 * the robot shouldn't receive them.
	 */
	private void rejectIfOversized(Order order, List<String> items) {
		if (orderLengthValidator.exceedsLimits(items)) {
			order.setOrderStatus(OrderStatus.ERROR);
			order.setError(OrderLengthValidator.ERROR_MESSAGE);
			log.warn("Webhook order {} (external id {}) rejected — {}",
					order.getId(), order.getExternalOrderId(), OrderLengthValidator.ERROR_MESSAGE);
		}
	}

	/**
	 * A new order arriving while the dispatch backlog already exceeds what
	 * the robot can drain within the immediate-cancel horizon would expire
	 * in the queue before its turn — saved as DROPPED up front instead of
	 * accept-then-cancel later.
	 */
	private void dropIfOverloaded(Order order) {
		if (order.getOrderStatus() == OrderStatus.CREATED && backlogEstimator.isOverloaded()) {
			order.setOrderStatus(OrderStatus.DROPPED);
			order.setError(DispatchBacklogEstimator.OVERLOAD_ERROR);
			log.warn("Webhook order {} (external id {}) DROPPED — {}",
					order.getId(), order.getExternalOrderId(), DispatchBacklogEstimator.OVERLOAD_ERROR);
		}
	}

	/**
	 * A record reusing a known order_id without an update flag is a content
	 * update to the earlier order — allowed only while the order is still
	 * CREATED (the DISPATCHED case is already screened out in process()).
	 */
	private void update(Order order, WebhookOrder payload) {
		if (order.getOrderStatus() != OrderStatus.CREATED) {
			log.warn("Cannot apply webhook update to order {} (external id {}) — status is {}, "
					+ "updates are only allowed while CREATED",
					order.getId(), payload.orderId(), order.getOrderStatus());
			return;
		}
		List<String> items = itemsOf(payload);
		order.setOrderSource(payload.orderSource());
		order.setRestaurant(payload.restaurant());
		order.setFirstName(payload.firstName());
		order.setLastName(payload.lastName());
		order.setAmount(payload.total());
		order.setItems(String.join(", ", items));
		order.setNotes(payload.notes());
		order.setUpdatedAt(Instant.now(clock));
		// an update that makes the order oversized errors it out just like an
		// oversized creation would (and blocks further updates: not CREATED)
		rejectIfOversized(order, items);
		// flush so Hibernate's @Version increment lands before we snapshot
		orderRepository.saveAndFlush(order);
		orderItemRepository.deleteByOrderId(order.getId());
		saveItems(order, items);
		historyRecorder.snapshot(order);
		log.info("Webhook order {} updated by source (now version {})", order.getId(), order.getVersion());
	}

	private List<String> itemsOf(WebhookOrder payload) {
		return payload.items() == null ? List.of() : payload.items();
	}

	private void saveItems(Order order, List<String> items) {
		for (String itemName : items) {
			if (!itemName.isBlank()) {
				orderItemRepository.save(OrderItem.of(order.getId(), itemName.strip(), null));
			}
		}
	}

	private void cancel(Order order) {
		if (order.getOrderStatus() == OrderStatus.CANCELLED) {
			return; // duplicate cancellation delivery — expected noise, not worth logging
		}
		order.setOrderStatus(OrderStatus.CANCELLED);
		order.setUpdatedAt(Instant.now(clock));
		// flush so Hibernate's @Version increment lands before we snapshot
		orderRepository.saveAndFlush(order);
		historyRecorder.snapshot(order);
		log.info("Webhook order {} cancelled by source", order.getId());
	}
}
