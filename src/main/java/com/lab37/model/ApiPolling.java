package com.lab37.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Single-row table holding the polling API's time_since cursor, persisted so
 * it is shared across app instances and survives restarts. Empty table means
 * "never polled" — the poller then starts from the epoch.
 */
@Entity
@Table(name = "api_polling")
public class ApiPolling {

	/** Always {@link #SINGLETON_ID}: this table has exactly one row. */
	public static final int SINGLETON_ID = 1;

	@Id
	private int id;

	/** Millis since epoch of the start of the last fully applied poll. */
	@Column(name = "last_polled", nullable = false)
	private long lastPolled;

	protected ApiPolling() {
		// for JPA
	}

	public static ApiPolling of(long lastPolledEpochMillis) {
		ApiPolling row = new ApiPolling();
		row.id = SINGLETON_ID;
		row.lastPolled = lastPolledEpochMillis;
		return row;
	}

	public long getLastPolled() {
		return lastPolled;
	}

	public void setLastPolled(long lastPolled) {
		this.lastPolled = lastPolled;
	}
}
