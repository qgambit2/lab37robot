package com.lab37.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "orders", indexes = {
		// backs findDispatchable: WHERE order_status = 'CREATED'
		// … ORDER BY vip DESC, created_at — VIP orders jump the queue
		@Index(name = "idx_orders_status_created", columnList = "order_status, vip DESC, created_at"),
		// backs findByJobId
		@Index(name = "idx_orders_job", columnList = "job_id"),
		// backs the webhook consumer's insert-if-absent idempotency check
		@Index(name = "idx_orders_external_id", columnList = "external_order_id", unique = true)})
public class Order {

	/** Column size for the denormalized items/notes strings; longer values are truncated. */
	public static final int TEXT_MAX_LENGTH = 4096;

	@Id
	private UUID id;

	/** Upload job that produced this order; only set for CSV (SVC_FILE) orders. */
	@Column(name = "job_id")
	private UUID jobId;

	/**
	 * The source system's own order id as a string (webhook UUID, polling-API
	 * numeric order number); null for CSV orders.
	 */
	@Column(name = "external_order_id", length = 64)
	private String externalOrderId;

	@Column(name = "first_name")
	private String firstName;

	@Column(name = "last_name")
	private String lastName;

	// items/notes are denormalized display strings — order_items is the
	// source of truth — so overlong values are truncated, never rejected
	@Column(length = TEXT_MAX_LENGTH)
	private String items;

	@Column(length = TEXT_MAX_LENGTH)
	private String notes;

	/** Whether the order is scheduled for tomorrow rather than today. */
	@Column(nullable = false)
	private boolean tomorrow;

	/**
	 * VIP orders jump the dispatch queue: the dispatcher picks them before
	 * any non-VIP order regardless of age. Settable while the order is still
	 * CREATED via PATCH /v1/orders/{id} — the escape hatch for an order that
	 * must not wait behind a busy queue.
	 */
	@Column(nullable = false)
	private boolean vip;

	private String meal;

	/**
	 * Order amount. Nullable: only some sources carry it (webhook orders do);
	 * CSV survey orders don't — their pricing is determined elsewhere.
	 */
	@Column(precision = 10, scale = 2)
	private BigDecimal amount;

	/** Originating aggregator (webhook orders: Overeats/DoorDrop/…); null for other sources. */
	@Column(name = "order_source")
	private String orderSource;

	/** Restaurant brand the order was placed with (webhook orders); null for other sources. */
	@Column
	private String restaurant;

	@Enumerated(EnumType.STRING)
	@Column(name = "order_type", nullable = false)
	private OrderType orderType;

	@Enumerated(EnumType.STRING)
	@Column(name = "order_status", nullable = false)
	private OrderStatus orderStatus;

	/** Why the order is UNFULFILLED (validation or scheduling problem), if any. */
	@Column
	private String error;

	/** When the order was actually sent to the robot; null until dispatch. */
	@Column(name = "dispatch_time")
	private Instant dispatchTime;

	/** Dispatch window computed from meal + tomorrow; null if unschedulable. */
	@Column(name = "dispatch_time_interval_start")
	private Instant dispatchTimeIntervalStart;

	@Column(name = "dispatch_time_interval_end")
	private Instant dispatchTimeIntervalEnd;

	/**
	 * Managed by Hibernate optimistic locking (@Version): every UPDATE carries
	 * "WHERE version = <read value>" and bumps it, so a lost update — two
	 * transactions writing from the same snapshot — fails at commit with
	 * OptimisticLockingFailureException instead of silently overwriting.
	 * Starts at 1 (set in the factories; Hibernate keeps a pre-set positive
	 * seed) and increments on every flush of a dirty order.
	 */
	@Version
	@Column(nullable = false)
	private int version;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Order() {
		// for JPA
	}

	/** An immediate order from the webhook stream (no meal/tomorrow, dispatch ASAP). */
	public static Order fromWebhook(String externalOrderId, String orderSource, String restaurant,
			String firstName, String lastName, BigDecimal total, String items, String notes) {
		Order order = new Order();
		order.id = UUID.randomUUID();
		order.externalOrderId = externalOrderId;
		order.orderSource = orderSource;
		order.restaurant = restaurant;
		order.firstName = firstName;
		order.lastName = lastName;
		order.amount = total;
		order.setItems(items);
		order.setNotes(notes);
		order.orderType = OrderType.WEBHOOK;
		order.orderStatus = OrderStatus.CREATED;
		order.version = 1;
		Instant now = Instant.now();
		order.createdAt = now;
		order.updatedAt = now;
		return order;
	}

	/**
	 * An order reconstructed from polling-API item deltas. Starts with no
	 * items/amount — the API processor fills those in as item entries arrive.
	 */
	public static Order fromApiPull(String externalOrderId) {
		Order order = new Order();
		order.id = UUID.randomUUID();
		order.externalOrderId = externalOrderId;
		order.orderType = OrderType.API_PULL;
		order.orderStatus = OrderStatus.CREATED;
		order.version = 1;
		Instant now = Instant.now();
		order.createdAt = now;
		order.updatedAt = now;
		return order;
	}

	public static Order fromCsv(UUID jobId, String firstName, String lastName, String items,
			String notes, boolean tomorrow, String meal) {
		Order order = new Order();
		order.id = UUID.randomUUID();
		order.jobId = jobId;
		order.firstName = firstName;
		order.lastName = lastName;
		order.setItems(items);
		order.setNotes(notes);
		order.tomorrow = tomorrow;
		order.meal = meal;
		order.orderType = OrderType.SVC_FILE;
		order.orderStatus = OrderStatus.CREATED;
		order.version = 1;
		Instant now = Instant.now();
		order.createdAt = now;
		order.updatedAt = now;
		return order;
	}

	public UUID getId() {
		return id;
	}

	public UUID getJobId() {
		return jobId;
	}

	public String getExternalOrderId() {
		return externalOrderId;
	}

	public String getFirstName() {
		return firstName;
	}

	public void setFirstName(String firstName) {
		this.firstName = firstName;
	}

	public String getLastName() {
		return lastName;
	}

	public void setLastName(String lastName) {
		this.lastName = lastName;
	}

	public String getItems() {
		return items;
	}

	public void setItems(String items) {
		this.items = truncateToColumn(items);
	}

	public String getNotes() {
		return notes;
	}

	public void setNotes(String notes) {
		this.notes = truncateToColumn(notes);
	}

	private String truncateToColumn(String value) {
		return value == null || value.length() <= TEXT_MAX_LENGTH
				? value
				: value.substring(0, TEXT_MAX_LENGTH);
	}

	public boolean isTomorrow() {
		return tomorrow;
	}

	public boolean isVip() {
		return vip;
	}

	public void setVip(boolean vip) {
		this.vip = vip;
	}

	public String getMeal() {
		return meal;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public void setAmount(BigDecimal amount) {
		this.amount = amount;
	}

	public String getOrderSource() {
		return orderSource;
	}

	public void setOrderSource(String orderSource) {
		this.orderSource = orderSource;
	}

	public String getRestaurant() {
		return restaurant;
	}

	public void setRestaurant(String restaurant) {
		this.restaurant = restaurant;
	}

	public OrderType getOrderType() {
		return orderType;
	}

	public OrderStatus getOrderStatus() {
		return orderStatus;
	}

	public void setOrderStatus(OrderStatus orderStatus) {
		this.orderStatus = orderStatus;
	}

	public String getError() {
		return error;
	}

	public void setError(String error) {
		this.error = error;
	}

	public Instant getDispatchTime() {
		return dispatchTime;
	}

	public void setDispatchTime(Instant dispatchTime) {
		this.dispatchTime = dispatchTime;
	}

	public Instant getDispatchTimeIntervalStart() {
		return dispatchTimeIntervalStart;
	}

	public Instant getDispatchTimeIntervalEnd() {
		return dispatchTimeIntervalEnd;
	}

	public void setDispatchTimeInterval(Instant start, Instant end) {
		this.dispatchTimeIntervalStart = start;
		this.dispatchTimeIntervalEnd = end;
	}

	public int getVersion() {
		return version;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}
}
