package com.lab37.model;

/** Lifecycle of an async intake-queue entry (webhook_queue, api_order_queue). */
public enum QueueStatus {
	/** Accepted and acked with 200; not yet turned into orders. */
	RECEIVED,
	/** Successfully converted into order rows. */
	PROCESSED,
	/** Transient failure (e.g. DB error); retried while retry_count is under the max. */
	ERROR,
	/** Terminal: expired past the processing deadline, or unparseable payload. Never retried. */
	PROCESSING_FAILURE
}
