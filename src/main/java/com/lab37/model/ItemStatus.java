package com.lab37.model;

/**
 * Our side of an item's lifecycle — deliberately just two states. Everything
 * the source system reports lives in source_status verbatim; this column only
 * records whether WE sent the item to the robot. Because items keep changing
 * after dispatch (source deltas advance source_status), this flag — snapshotted
 * into item_history per version — is the durable record of exactly which items
 * a dispatch included.
 */
public enum ItemStatus {
	/** As ingested; not (yet) part of a robot dispatch. */
	CREATED,
	/** Included in the robot dispatch payload. */
	DISPATCHED
}
