package com.lab37.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.lab37.model.OrdersProcessed;

public interface OrdersProcessedRepository extends JpaRepository<OrdersProcessed, String> {

	/**
	 * Minute buckets in [from, to], oldest first. Bucket ids are
	 * yyyyMMddHHmm strings, so lexicographic order is chronological.
	 */
	List<OrdersProcessed> findByTimeBetweenOrderByTimeAsc(String from, String to);
}
