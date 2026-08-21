package com.lab37.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.lab37.model.WebhookQueue;

public interface WebhookQueueRepository extends JpaRepository<WebhookQueue, UUID> {

	/**
	 * Entries awaiting processing: fresh (RECEIVED) or transiently failed
	 * (ERROR) with retries left — oldest first, so no entry starves.
	 */
	@Query("""
			select w from WebhookQueue w
			where (w.status = com.lab37.model.QueueStatus.RECEIVED
			    or w.status = com.lab37.model.QueueStatus.ERROR)
			  and w.retryCount < :maxRetries
			order by w.createdOn asc
			""")
	List<WebhookQueue> findProcessable(@Param("maxRetries") int maxRetries);
}
