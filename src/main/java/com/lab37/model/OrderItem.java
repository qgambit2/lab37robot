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
 * One line item (dish) of an order. CSV orders split their comma-separated
 * items field into rows with no price (pricing is determined elsewhere);
 * the polling-API pipeline (later) will supply per-item prices and its own
 * external item ids.
 */
@Entity
@Table(name = "order_items", indexes = {
		// backs findByOrderId (fetching an order's dishes)
		@Index(name = "idx_order_items_order", columnList = "order_id"),
		// backs the polling-API consumer's item upsert matching
		@Index(name = "idx_order_items_external_id", columnList = "external_item_id", unique = true)})
public class OrderItem {

	/** Column size for a single item name; longer names are truncated. */
	public static final int NAME_MAX_LENGTH = 1024;

	@Id
	private UUID id;

	@Column(name = "order_id", nullable = false)
	private UUID orderId;

	@Column(name = "item_name", nullable = false, length = NAME_MAX_LENGTH)
	private String itemName;

	@Column(name = "item_price", precision = 10, scale = 2)
	private BigDecimal itemPrice;

	/** CREATED until the item is sent to the robot, then DISPATCHED — see {@link ItemStatus}. */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ItemStatus status;

	/**
	 * The polling API's item id — a 64-char hex hash, hence the generous
	 * length. Null for CSV/webhook items, which have no external identity.
	 */
	@Column(name = "external_item_id", length = 128)
	private String externalItemId;

	/**
	 * The source system's own item status, verbatim (polling API:
	 * ordered/processing/with_courier/delivered/cancelled). Null for
	 * CSV/webhook items, which is fine — there is no our-side item status.
	 * Only items with no source status or source status "ordered" are
	 * eligible for the robot dispatch payload — a cancelled item must not be
	 * made, and anything further along is already being made or delivered by
	 * the source's own flow.
	 */
	@Column(name = "source_status", length = 32)
	private String sourceStatus;

	protected OrderItem() {
		// for JPA
	}

	public static OrderItem of(UUID orderId, String itemName, BigDecimal itemPrice) {
		OrderItem item = new OrderItem();
		item.id = UUID.randomUUID();
		item.orderId = orderId;
		item.itemName = itemName != null && itemName.length() > NAME_MAX_LENGTH
				? itemName.substring(0, NAME_MAX_LENGTH)
				: itemName;
		item.itemPrice = itemPrice;
		item.status = ItemStatus.CREATED;
		return item;
	}

	/** An item from the polling API, carrying its external id and source status. */
	public static OrderItem fromApi(UUID orderId, String itemName, BigDecimal itemPrice,
			String externalItemId, String sourceStatus) {
		OrderItem item = of(orderId, itemName, itemPrice);
		item.externalItemId = externalItemId;
		item.sourceStatus = sourceStatus;
		return item;
	}

	public UUID getId() {
		return id;
	}

	@JsonIgnore // items are only ever serialized nested under their order
	public UUID getOrderId() {
		return orderId;
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

	public void setStatus(ItemStatus status) {
		this.status = status;
	}

	public String getExternalItemId() {
		return externalItemId;
	}

	public String getSourceStatus() {
		return sourceStatus;
	}

	public void setSourceStatus(String sourceStatus) {
		this.sourceStatus = sourceStatus;
	}
}
