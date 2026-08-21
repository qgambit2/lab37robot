package com.lab37.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Per-minute dispatch counter used for robot rate limiting (DB standing in
 * for what would be Redis in production). One row per minute; the sliding
 * window reads the current and previous minute's rows.
 */
@Entity
@Table(name = "orders_processed")
public class OrdersProcessed {

	/** Minute bucket in yyyyMMddHHmm format. */
	@Id
	@Column(name = "time", length = 12)
	private String time;

	@Column(nullable = false)
	private int count;

	protected OrdersProcessed() {
		// for JPA
	}

	public static OrdersProcessed forMinute(String time) {
		OrdersProcessed row = new OrdersProcessed();
		row.time = time;
		row.count = 0;
		return row;
	}

	public String getTime() {
		return time;
	}

	public int getCount() {
		return count;
	}

	public void increment() {
		this.count++;
	}
}
