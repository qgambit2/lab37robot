package com.lab37.service;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dispatch configuration under {@code dispatch.*} in application.yaml.
 *
 * @param windows meal dispatch windows, keyed by meal name (case-insensitive)
 * @param immediateCancelAfter how long an immediate order (webhook / polling
 *        API — no meal/tomorrow, dispatch ASAP) may wait for the robot before
 *        being cancelled; also the freshness horizon when picking such orders
 *        for dispatch (measured from the order's last update)
 * @param maxPerMinute robot rate limit: at most this many dispatches per
 *        minute (sliding window)
 */
@ConfigurationProperties(prefix = "dispatch")
public record DispatchProperties(Map<String, MealWindow> windows, Duration immediateCancelAfter,
		Integer maxPerMinute) {

	public DispatchProperties {
		if (immediateCancelAfter == null) {
			immediateCancelAfter = Duration.ofMinutes(30);
		}
		if (maxPerMinute == null) {
			maxPerMinute = 100;
		}
	}

	/**
	 * Wall-clock window bounds as strings: "HH:mm" optionally followed by a
	 * zone ("07:00 PST", "11:30 America/New_York"); no zone means UTC. Kept
	 * as raw strings here — DispatchWindowCalculator parses and validates
	 * them at startup. A zone-tagged window tracks that zone's DST rules
	 * ("07:00 PST" is Pacific breakfast year-round).
	 */
	public record MealWindow(String start, String end) {
	}
}
