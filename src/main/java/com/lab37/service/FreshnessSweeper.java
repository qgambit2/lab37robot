package com.lab37.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lab37.model.Order;
import com.lab37.model.OrderStatus;
import com.lab37.repository.OrderRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Terminally closes orders the dispatcher will never pick up again. Stale
 * CREATED orders — a CSV order whose meal window closed, an immediate order
 * that aged past dispatch.immediate-cancel-after — merely stop matching the
 * dispatch pickup query; without this sweep they would linger as
 * apparently-live CREATED rows forever. Every 15 minutes
 * (dispatch.sweep-interval) they are flipped to UNFULFILLED with the reason
 * recorded, WARN-logged like every other fulfillment failure, and
 * history-snapshotted. VIP orders are never swept (they are due
 * immediately); a source update revives an immediate order by resetting its
 * freshness clock before the sweep reaches it.
 */
@Service
public class FreshnessSweeper {

	public static final String TIMED_OUT_ERROR = "not dispatched in time";

	private static final Logger log = LoggerFactory.getLogger(FreshnessSweeper.class);

	private final OrderRepository orderRepository;
	private final HistoryRecorder historyRecorder;
	private final DispatchProperties dispatchProperties;
	private final Clock clock;

	public FreshnessSweeper(OrderRepository orderRepository,
			HistoryRecorder historyRecorder,
			DispatchProperties dispatchProperties,
			Clock clock) {
		this.orderRepository = orderRepository;
		this.historyRecorder = historyRecorder;
		this.dispatchProperties = dispatchProperties;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${dispatch.sweep-interval}")
	@SchedulerLock(name = "sweepStaleOrders", lockAtMostFor = "5m")
	@Transactional
	public void sweepStaleOrders() {
		Instant now = Instant.now(clock);
		List<Order> stale = orderRepository.findStaleForUpdate(now,
				now.minus(dispatchProperties.immediateCancelAfter()));
		for (Order order : stale) {
			order.setOrderStatus(OrderStatus.UNFULFILLED);
			order.setError(TIMED_OUT_ERROR);
			order.setUpdatedAt(now);
			// flush so Hibernate's @Version increment lands before we snapshot
			orderRepository.saveAndFlush(order);
			historyRecorder.snapshot(order);
			log.warn("Order {} UNFULFILLED — {}", order.getId(), TIMED_OUT_ERROR);
		}
		if (!stale.isEmpty()) {
			log.info("Freshness sweep closed {} stale order(s)", stale.size());
		}
	}
}
