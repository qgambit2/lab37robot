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
 * Inbound webhook payloads, persisted verbatim so the endpoint can ack with
 * 200 immediately; a scheduled job converts them into orders asynchronously
 * (reading status = RECEIVED, retry_count below the configured max, oldest
 * first — hence the (status, created_on) index). The DB stands in for what
 * would be a real queue with retries (e.g. SQS) in production.
 */
@Entity
@Table(name = "webhook_queue", indexes =
		@Index(name = "idx_webhook_queue_status_created", columnList = "status, created_on"))
public class WebhookQueue {

	@Id
	private UUID id;

	@Column(name = "created_on", nullable = false)
	private Instant createdOn;

	/** The raw webhook request body, exactly as received. */
	@Column(nullable = false, columnDefinition = "text")
	private String payload;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private QueueStatus status;

	@Column(name = "retry_count", nullable = false)
	private int retryCount;

	/** Why processing failed (terminal reason, or the latest transient error). */
	@Column
	private String error;

	protected WebhookQueue() {
		// for JPA
	}

	public static WebhookQueue received(String payload, Instant createdOn) {
		WebhookQueue entry = new WebhookQueue();
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
