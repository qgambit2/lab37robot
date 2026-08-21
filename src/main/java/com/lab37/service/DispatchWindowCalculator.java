package com.lab37.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.lab37.service.DispatchProperties.MealWindow;

/**
 * Computes the robot dispatch window for an order from its meal and its
 * tomorrow flag. Windows are wall-clock times in the zone each window names
 * ("07:00 PST" — no zone means UTC), configured under dispatch.windows in
 * application.yaml. "Today" and the UTC instants of the window bounds are
 * resolved IN THAT ZONE per day, so a zoned window tracks its zone's DST
 * rules — Pacific breakfast is 7am Pacific in January (UTC-8) and in July
 * (UTC-7) alike, instead of silently shifting by an hour. Bad window config
 * (unparseable time, unknown zone, mismatched start/end zones) fails at
 * startup, not at scheduling time.
 */
@Component
public class DispatchWindowCalculator {

	public record Window(Instant start, Instant end) {
	}

	/** Accepts "7:00" and "07:00". */
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm");

	private record ZonedWindow(LocalTime start, LocalTime end, ZoneId zone) {
	}

	private final Map<String, ZonedWindow> mealWindows;
	private final Clock clock;

	public DispatchWindowCalculator(DispatchProperties properties, Clock clock) {
		if (properties.windows() == null || properties.windows().isEmpty()) {
			throw new IllegalStateException("dispatch.windows must be configured");
		}
		this.mealWindows = properties.windows().entrySet().stream()
				.collect(Collectors.toUnmodifiableMap(
						entry -> entry.getKey().toLowerCase(Locale.ROOT),
						entry -> parse(entry.getKey(), entry.getValue())));
		this.clock = clock;
	}

	public boolean isKnownMeal(String meal) {
		return meal != null && mealWindows.containsKey(meal.toLowerCase(Locale.ROOT));
	}

	/**
	 * The dispatch window for this meal — today's, or tomorrow's when the
	 * tomorrow flag is set, with "today" determined in the window's own zone.
	 * Empty when the order can no longer be scheduled: a same-day order whose
	 * window has already ended (past cutoff). An order arriving with its
	 * window in progress is still schedulable ("process as many as we can"
	 * until the window closes).
	 */
	public Optional<Window> windowFor(String meal, boolean tomorrow) {
		ZonedWindow window = mealWindows.get(meal.toLowerCase(Locale.ROOT));
		if (window == null) {
			throw new IllegalArgumentException("Unknown meal: " + meal);
		}
		Instant now = Instant.now(clock);
		LocalDate day = ZonedDateTime.ofInstant(now, window.zone()).toLocalDate()
				.plusDays(tomorrow ? 1 : 0);
		Instant end = day.atTime(window.end()).atZone(window.zone()).toInstant();
		if (!tomorrow && !now.isBefore(end)) {
			return Optional.empty();
		}
		Instant start = day.atTime(window.start()).atZone(window.zone()).toInstant();
		return Optional.of(new Window(start, end));
	}

	private ZonedWindow parse(String meal, MealWindow window) {
		Bound start = parseBound(meal, "start", window.start());
		Bound end = parseBound(meal, "end", window.end());
		if (!start.zone().equals(end.zone())) {
			throw new IllegalStateException("dispatch.windows." + meal
					+ ": start and end must use the same zone (got "
					+ start.zone() + " and " + end.zone() + ")");
		}
		return new ZonedWindow(start.time(), end.time(), start.zone());
	}

	private record Bound(LocalTime time, ZoneId zone) {
	}

	/** "HH:mm" (UTC) or "HH:mm ZONE" — ZONE a short id (PST, EST, …) or a full zone id. */
	private Bound parseBound(String meal, String field, String value) {
		try {
			String[] parts = value.strip().split("\\s+", 2);
			LocalTime time = LocalTime.parse(parts[0], TIME);
			// SHORT_IDS maps abbreviations to real zones (PST → America/
			// Los_Angeles), so "PST" follows Pacific DST rather than a
			// fixed -08:00
			ZoneId zone = parts.length == 1
					? ZoneOffset.UTC
					: ZoneId.of(parts[1], ZoneId.SHORT_IDS);
			return new Bound(time, zone);
		} catch (Exception e) {
			throw new IllegalStateException("dispatch.windows." + meal + "." + field
					+ ": cannot parse '" + value + "' — expected \"HH:mm\" or \"HH:mm ZONE\"", e);
		}
	}
}
