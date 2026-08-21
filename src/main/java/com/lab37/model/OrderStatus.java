package com.lab37.model;

public enum OrderStatus {
	/** Saved and (if schedulable) waiting for its dispatch window. */
	CREATED,
	/** Will not be dispatched — arrived after its window's cutoff, or missed robot capacity. */
	UNFULFILLED,
	/** Sent to the robot with ALL of its items. */
	DISPATCHED,
	/**
	 * Sent to the robot, but not with all of its items — some were excluded
	 * (cancelled, or already being handled by the source). A distinct status
	 * so partially-made orders are directly searchable.
	 */
	PARTIALLY_DISPATCHED,
	/** Cancelled by the source before dispatch (e.g. webhook update: ["cancelled"]). */
	CANCELLED,
	/** Rejected by validation (e.g. oversized item list) — never sent to the robot. */
	ERROR,
	/**
	 * Rejected at intake because the dispatch backlog already exceeded what
	 * the robot can drain in time ("system overload") — never queued for
	 * dispatch. A distinct status so overload rejections are directly
	 * countable in metrics.
	 */
	DROPPED
}
