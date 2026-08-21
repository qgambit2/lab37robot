package com.lab37.service;

import java.util.Collection;

import org.springframework.stereotype.Component;

import com.lab37.model.Order;
import com.lab37.model.OrderItem;

/**
 * Guards the robot from oversized orders. An order whose combined item list
 * (joined with ", ") exceeds {@link Order#TEXT_MAX_LENGTH}, or with any
 * single item name over {@link OrderItem#NAME_MAX_LENGTH}, is rejected at
 * ingestion — saved with status ERROR and never dispatched — rather than
 * truncated into a request that could confuse the robot. All three ingestion
 * pipelines (webhook, polling API, CSV) run their item names through this
 * check.
 */
@Component
public class OrderLengthValidator {

	public static final String ERROR_MESSAGE = "order length exceeds maximum limit";

	public boolean exceedsLimits(Collection<String> itemNames) {
		int joinedLength = 0;
		for (String name : itemNames) {
			if (name.length() > OrderItem.NAME_MAX_LENGTH) {
				return true;
			}
			joinedLength += (joinedLength == 0 ? 0 : 2) + name.length(); // ", " between names
		}
		return joinedLength > Order.TEXT_MAX_LENGTH;
	}
}
