package com.lab37.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Polling-API responses queued for async processing — the polling twin of
 * webhook_queue (separate table because the payload shape differs). One row
 * per response, holding the raw body: a single atomic insert, so a response
 * can never be half-enqueued. The consumer parses, groups by order, and
 * applies; failures are retried from here rather than by re-polling the
 * window.
 */
@Entity
@Table(name = "api_order_queue", indexes =
		@Index(name = "idx_api_order_queue_status_created", columnList = "status, created_on"))
public class ApiOrderQueue {

	@Id
	private UUID id;

	@Column(name = "created_on", nullable = false)
	private Instant createdOn;

	/** The raw polling-API response body, exactly as received. */
	@Column(nullable = false, columnDefinition = "text")
	private String payload;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private QueueStatus status;

	@Column(name = "retry_count", nullable = false)
	private int retryCount;

	@Column
	private String error;

	protected ApiOrderQueue() {
		// for JPA
	}

	public static ApiOrderQueue received(String payload, Instant createdOn) {
		ApiOrderQueue entry = new ApiOrderQueue();
		entry.id = UUID.randomUUID();
		entry.createdOn = createdOn;
		entry.payload = payload;
		entry.status = QueueStatus.RECEIVED;
		entry.retryCount = 0;
		return entry;
	}

	public UUID getId() {
		return id;
	}

	public Instant getCreatedOn() {
		return createdOn;
	}

	public String getPayload() {
		return payload;
	}

	/** Used by the consumer to shrink a partially failed entry to its failed orders. */
	public void setPayload(String payload) {
		this.payload = payload;
	}

	public QueueStatus getStatus() {
		return status;
	}

	public void setStatus(QueueStatus status) {
		this.status = status;
	}

	public int getRetryCount() {
		return retryCount;
	}

	public void setRetryCount(int retryCount) {
		this.retryCount = retryCount;
	}

	public String getError() {
		return error;
	}

	public void setError(String error) {
		this.error = error;
	}
}
