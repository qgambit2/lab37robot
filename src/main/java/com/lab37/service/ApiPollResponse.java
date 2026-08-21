package com.lab37.service;

import java.math.BigDecimal;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One polling-API response (see api_responses.jsonl): a delta of item-level
 * changes since the supplied time_since. Every key under {@code data} is the
 * source's item id (a 64-char hex hash); each value is that item's full
 * current state, with {@code order} being the source's numeric order id.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiPollResponse(int response, String error, Map<String, ApiPollItem> data) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ApiPollItem(long order, String name, String category, BigDecimal price,
			String status) {
	}
}
