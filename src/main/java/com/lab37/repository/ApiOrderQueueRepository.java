package com.lab37.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.lab37.model.ApiOrderQueue;

public interface ApiOrderQueueRepository extends JpaRepository<ApiOrderQueue, UUID> {

	/** Fresh or transiently failed entries with retries left, oldest first. */
	@Query("""
			select q from ApiOrderQueue q
			where (q.status = com.lab37.model.QueueStatus.RECEIVED
			    or q.status = com.lab37.model.QueueStatus.ERROR)
			  and q.retryCount < :maxRetries
			order by q.createdOn asc
			""")
	List<ApiOrderQueue> findProcessable(@Param("maxRetries") int maxRetries);
}
