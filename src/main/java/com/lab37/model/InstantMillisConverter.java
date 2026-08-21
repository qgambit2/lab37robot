package com.lab37.model;

import java.time.Instant;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists every Instant attribute as milliseconds since the epoch (BIGINT)
 * instead of a SQL timestamp — auto-applied to all entities, so no DB
 * timestamp column can carry timezone ambiguity. (The shedlock table is the
 * one exception: its TIMESTAMP columns are ShedLock's own schema contract.)
 */
@Converter(autoApply = true)
public class InstantMillisConverter implements AttributeConverter<Instant, Long> {

	@Override
	public Long convertToDatabaseColumn(Instant attribute) {
		return attribute == null ? null : attribute.toEpochMilli();
	}

	@Override
	public Instant convertToEntityAttribute(Long dbData) {
		return dbData == null ? null : Instant.ofEpochMilli(dbData);
	}
}
