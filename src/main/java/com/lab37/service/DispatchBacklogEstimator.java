package com.lab37.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

import com.lab37.repository.OrderRepository;

/**
 * Estimates whether the dispatch backlog is already deeper than the robot
 * can drain in time. The backlog is the count of orders currently competing
 * for robot slots (the same predicate the dispatch pickup uses); the robot
 * drains at most dispatch.max-per-minute per minute; an immediate order may
 * wait at most dispatch.immediate-cancel-after. When backlog / rate exceeds
 * that horizon, an immediate order accepted now would expire in the queue
 * before its turn — so intake rejects it up front as UNFULFILLED
 * ("system overload") instead of accept-then-cancel half an hour later.
 */
@Component
public class DispatchBacklogEstimator {

	public static final String OVERLOAD_ERROR = "system overload";

	private final OrderRepository orderRepository;
	private final DispatchProperties dispatchProperties;
	private final Clock clock;

	public DispatchBacklogEstimator(OrderRepository orderRepository,
			DispatchProperties dispatchProperties, Clock clock) {
		this.orderRepository = orderRepository;
		this.dispatchProperties = dispatchProperties;
		this.clock = clock;
	}

	public boolean isOverloaded() {
		Instant now = Instant.now(clock);
		long backlog = orderRepository.countDispatchable(now,
				now.minus(dispatchProperties.immediateCancelAfter()));
		// max orders the robot can dispatch within the cancel horizon
		long drainable = (long) dispatchProperties.maxPerMinute()
				* dispatchProperties.immediateCancelAfter().toMinutes();
		return backlog >= drainable;
	}
}
