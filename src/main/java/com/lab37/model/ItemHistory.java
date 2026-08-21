package com.lab37.model;

import java.math.BigDecimal;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Snapshot of one order item at one order version — the item-level companion
 * of {@link OrderHistory}. Whenever an order history snapshot is written, the
 * order's current items are snapshotted alongside it under the same
 * order_version (see HistoryRecorder), so the history API can show exactly
 * which items an order had, and in what state, at every step of its life.
 */
@Entity
@Table(name = "item_history", indexes = {
		// backs findByOrderIdOrderByOrderVersion (an order's item timeline)
		@Index(name = "idx_item_history_order", columnList = "order_id, order_version")})
public class ItemHistory {

	@Id
	private UUID id;

	@Column(name = "order_id", nullable = false)
	private UUID orderId;

	/** The order version this snapshot belongs to (order_history.version). */
	@Column(name = "order_version", nullable = false)
	private int orderVersion;

	/** The order_items row this snapshot was taken from. */
	@Column(name = "item_id", nullable = false)
	private UUID itemId;

	@Column(name = "item_name", nullable = false, length = OrderItem.NAME_MAX_LENGTH)
	private String itemName;

	@Column(name = "item_price", precision = 10, scale = 2)
	private BigDecimal itemPrice;

	/** Our dispatch flag at this version — see {@link ItemStatus}. */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ItemStatus status;

	@Column(name = "external_item_id", length = 128)
	private String externalItemId;

	@Column(name = "source_status", length = 32)
	private String sourceStatus;

	protected ItemHistory() {
		// for JPA
	}

	public static ItemHistory of(OrderItem item, int orderVersion) {
		ItemHistory history = new ItemHistory();
		history.id = UUID.randomUUID();
		history.orderId = item.getOrderId();
		history.orderVersion = orderVersion;
		history.itemId = item.getId();
		history.itemName = item.getItemName();
		history.itemPrice = item.getItemPrice();
		history.status = item.getStatus();
		history.externalItemId = item.getExternalItemId();
		history.sourceStatus = item.getSourceStatus();
		return history;
	}

	public UUID getId() {
		return id;
	}

	@JsonIgnore // snapshots are only ever serialized nested under their order snapshot
	public UUID getOrderId() {
		return orderId;
	}

	@JsonIgnore // ditto: the wrapping history entry's order carries the version
	public int getOrderVersion() {
		return orderVersion;
	}

	public UUID getItemId() {
		return itemId;
	}

	public String getItemName() {
		return itemName;
	}

	public BigDecimal getItemPrice() {
		return itemPrice;
	}

	public ItemStatus getStatus() {
		return status;
	}

	public String getExternalItemId() {
		return externalItemId;
	}

	public String getSourceStatus() {
		return sourceStatus;
	}
}
