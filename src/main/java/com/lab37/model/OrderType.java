package com.lab37.model;

/** Which ingestion pipeline an order arrived through. */
public enum OrderType {
	SVC_FILE,
	WEBHOOK,
	API_PULL
}
