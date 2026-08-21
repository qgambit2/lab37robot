package com.lab37.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.lab37.model.Order;
import com.lab37.model.OrderStatus;
import com.lab37.model.OrderType;

import jakarta.persistence.LockModeType;

public interface OrderRepository extends JpaRepository<Order, UUID> {

	List<Order> findByJobId(UUID jobId);

	Optional<Order> findByExternalOrderId(String externalOrderId);

	/**
	 * Row-locked (FOR UPDATE) lookup used by writers that must not interleave:
	 * the webhook updater and the robot dispatcher both read through a
	 * pessimistic lock, so an order being updated can't simultaneously be
	 * picked up for dispatch, and vice versa — the loser of the lock sees the
	 * winner's committed state.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select o from Order o where o.externalOrderId = :externalOrderId")
	Optional<Order> findByExternalOrderIdForUpdate(@Param("externalOrderId") String externalOrderId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select o from Order o where o.id = :id")
	Optional<Order> findByIdForUpdate(@Param("id") UUID id);

	/**
	 * Orders due for robot dispatch: VIP orders first (they jump the queue),
	 * then oldest first. A VIP order is due IMMEDIATELY — it overrides the
	 * meal window and the freshness horizon ("VIP orders don't wait").
	 * Otherwise eligibility compares against the dispatch interval STORED at
	 * ingestion (never recomputed): CSV (SVC_FILE) orders must be inside
	 * their meal window right now; immediate orders (webhook / polling API)
	 * must have been created or updated within the expiry horizon.
	 */
	@Query("""
			select o from Order o
			where o.orderStatus = com.lab37.model.OrderStatus.CREATED
			  and (o.vip = true
			    or (o.orderType = com.lab37.model.OrderType.SVC_FILE
			        and o.dispatchTimeIntervalStart <= :now
			        and o.dispatchTimeIntervalEnd > :now)
			    or (o.orderType <> com.lab37.model.OrderType.SVC_FILE
			        and o.updatedAt >= :expiryCutoff))
			order by o.vip desc, o.createdAt asc
			""")
	List<Order> findDispatchable(@Param("now") Instant now,
			@Param("expiryCutoff") Instant expiryCutoff, Limit limit);

	/**
	 * Order search backing GET /v1/orders: every filter is optional (null =
	 * not filtered), the time filters mean "at or after". Callers pass at
	 * most one of createdAfter/updatedAfter; sorting/paging come from the
	 * Pageable.
	 */
	@Query("""
			select o from Order o
			where (:status is null or o.orderStatus = :status)
			  and (:orderType is null or o.orderType = :orderType)
			  and (:orderId is null or o.id = :orderId)
			  and (:externalOrderId is null or o.externalOrderId = :externalOrderId)
			  and (:createdAfter is null or o.createdAt >= :createdAfter)
			  and (:updatedAfter is null or o.updatedAt >= :updatedAfter)
			""")
	Page<Order> search(@Param("status") OrderStatus status,
			@Param("orderType") OrderType orderType,
			@Param("orderId") UUID orderId,
			@Param("externalOrderId") String externalOrderId,
			@Param("createdAfter") Instant createdAfter,
			@Param("updatedAfter") Instant updatedAfter, Pageable pageable);

	/**
	 * Stale CREATED orders the dispatcher will never pick up again: CSV
	 * orders whose meal window has closed, immediate orders that aged past
	 * the freshness horizon. VIP orders are excluded — they are due
	 * immediately, so they can't be stale. Row-locked so the sweep
	 * serializes with the dispatcher and the updaters.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select o from Order o
			where o.orderStatus = com.lab37.model.OrderStatus.CREATED
			  and o.vip = false
			  and ((o.orderType = com.lab37.model.OrderType.SVC_FILE
			        and o.dispatchTimeIntervalEnd <= :now)
			    or (o.orderType <> com.lab37.model.OrderType.SVC_FILE
			        and o.updatedAt < :expiryCutoff))
			""")
	List<Order> findStaleForUpdate(@Param("now") Instant now,
			@Param("expiryCutoff") Instant expiryCutoff);

	/**
	 * Size of the current dispatch backlog: same predicates as
	 * findDispatchable, so it counts exactly the orders competing for robot
	 * slots right now. Backs the intake overload check.
	 */
	@Query("""
			select count(o) from Order o
			where o.orderStatus = com.lab37.model.OrderStatus.CREATED
			  and (o.vip = true
			    or (o.orderType = com.lab37.model.OrderType.SVC_FILE
			        and o.dispatchTimeIntervalStart <= :now
			        and o.dispatchTimeIntervalEnd > :now)
			    or (o.orderType <> com.lab37.model.OrderType.SVC_FILE
			        and o.updatedAt >= :expiryCutoff))
			""")
	long countDispatchable(@Param("now") Instant now, @Param("expiryCutoff") Instant expiryCutoff);
}
