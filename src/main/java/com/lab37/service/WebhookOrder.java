package com.lab37.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One webhook payload as sent by the delivery aggregators (see
 * webhook_orders.jsonl). The optional {@code update} field marks
 * modifications to an earlier order — currently only {@code ["cancelled"]}
 * appears in the stream.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookOrder(
		@JsonProperty("order_id") UUID orderId,
		@JsonProperty("order_source") String orderSource,
		String restaurant,
		@JsonProperty("first_name") String firstName,
		@JsonProperty("last_name") String lastName,
		BigDecimal total,
		List<String> items,
		String notes,
		List<String> update) {

	public boolean isCancellation() {
		return update != null && update.contains("cancelled");
	}
}
