package com.lab37.service;

import java.util.List;
import java.util.UUID;

/**
 * Skeleton of the payload sent to the robot for order assembly:
 * {"orderId": "...", "items": [{"itemId": "...", "itemName": "dish"}, ...]}.
 * Cancelled items are excluded before this request is built.
 *
 * <p>Each item carries our order_items id so the robot can report back
 * per-item (progress, failures) keyed by an id we can match to our rows.
 */
public record RobotDispatchRequest(UUID orderId, List<Item> items) {

	/** One dish for the robot to make, keyed by our order_items id. */
	public record Item(UUID itemId, String itemName) {
	}
}
