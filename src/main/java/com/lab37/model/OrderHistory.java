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

/**
 * Point-in-time snapshot of an order — one row per order version. Mirrors the
 * orders table (minus updated_at) plus order_id linking back to the order.
 */
@Entity
// index backs findByOrderIdOrderByVersion (an order's version history)
@Table(name = "order_history", indexes =
		@Index(name = "idx_order_history_order_version", columnList = "order_id, version"))
public class OrderHistory {

	@Id
	private UUID id;

	@Column(name = "order_id", nullable = false)
	private UUID orderId;

	@Column(name = "job_id")
	private UUID jobId;

	@Column(name = "external_order_id", length = 64)
	private String externalOrderId;

	@Column(name = "first_name")
	private String firstName;

	@Column(name = "last_name")
	private String lastName;

	// copied from Order, whose setters already truncate to this size
	@Column(length = Order.TEXT_MAX_LENGTH)
	private String items;

	@Column(length = Order.TEXT_MAX_LENGTH)
	private String notes;

	@Column(nullable = false)
	private boolean tomorrow;

	@Column(nullable = false)
	private boolean vip;

	private String meal;

	@Column(precision = 10, scale = 2)
	private BigDecimal amount;

	@Column(name = "order_source")
	private String orderSource;

	@Column
	private String restaurant;

	@Enumerated(EnumType.STRING)
	@Column(name = "order_type", nullable = false)
	private OrderType orderType;

	@Enumerated(EnumType.STRING)
	@Column(name = "order_status", nullable = false)
	private OrderStatus orderStatus;

	@Column
	private String error;

	@Column(name = "dispatch_time")
	private Instant dispatchTime;

	@Column(name = "dispatch_time_interval_start")
	private Instant dispatchTimeIntervalStart;

	@Column(name = "dispatch_time_interval_end")
	private Instant dispatchTimeIntervalEnd;

	@Column(nullable = false)
	private int version;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected OrderHistory() {
		// for JPA
	}

	public static OrderHistory of(Order order) {
		OrderHistory history = new OrderHistory();
		history.id = UUID.randomUUID();
		history.orderId = order.getId();
		history.jobId = order.getJobId();
		history.externalOrderId = order.getExternalOrderId();
		history.firstName = order.getFirstName();
		history.lastName = order.getLastName();
		history.items = order.getItems();
		history.notes = order.getNotes();
		history.tomorrow = order.isTomorrow();
		history.vip = order.isVip();
		history.meal = order.getMeal();
		history.amount = order.getAmount();
		history.orderSource = order.getOrderSource();
		history.restaurant = order.getRestaurant();
		history.orderType = order.getOrderType();
		history.orderStatus = order.getOrderStatus();
		history.error = order.getError();
		history.dispatchTime = order.getDispatchTime();
		history.dispatchTimeIntervalStart = order.getDispatchTimeIntervalStart();
		history.dispatchTimeIntervalEnd = order.getDispatchTimeIntervalEnd();
		history.version = order.getVersion();
		history.createdAt = Instant.now();
		return history;
	}

	public UUID getId() {
		return id;
	}

	public UUID getOrderId() {
		return orderId;
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

	public String getLastName() {
		return lastName;
	}

	public String getItems() {
		return items;
	}

	public String getNotes() {
		return notes;
	}

	public boolean isVip() {
		return vip;
	}

	public boolean isTomorrow() {
		return tomorrow;
	}

	public String getMeal() {
		return meal;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getOrderSource() {
		return orderSource;
	}

	public String getRestaurant() {
		return restaurant;
	}

	public OrderType getOrderType() {
		return orderType;
	}

	public OrderStatus getOrderStatus() {
		return orderStatus;
	}

	public String getError() {
		return error;
	}

	public Instant getDispatchTime() {
		return dispatchTime;
	}

	public Instant getDispatchTimeIntervalStart() {
		return dispatchTimeIntervalStart;
	}

	public Instant getDispatchTimeIntervalEnd() {
		return dispatchTimeIntervalEnd;
	}

	public int getVersion() {
		return version;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
