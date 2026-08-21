package com.lab37.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.lab37.service.DispatchProperties.MealWindow;
import com.lab37.service.DispatchWindowCalculator.Window;

class DispatchWindowCalculatorTest {

	private static final ZoneId ZONE = ZoneOffset.UTC;

	static DispatchProperties defaultWindows() {
		// zone-less bounds mean UTC
		return new DispatchProperties(Map.of(
				"breakfast", new MealWindow("07:00", "09:00"),
				"lunch", new MealWindow("11:30", "13:30"),
				"dinner", new MealWindow("17:30", "19:30")),
				Duration.ofMinutes(30), 100);
	}

	private static DispatchWindowCalculator atLocalTime(int hour, int minute) {
		Instant now = ZonedDateTime.of(2026, 8, 19, hour, minute, 0, 0, ZONE).toInstant();
		return new DispatchWindowCalculator(defaultWindows(), Clock.fixed(now, ZONE));
	}

	private static Instant local(int day, int hour, int minute) {
		return ZonedDateTime.of(2026, 8, day, hour, minute, 0, 0, ZONE).toInstant();
	}

	@Test
	void sameDayOrderBeforeWindowGetsTodaysWindow() {
		Optional<Window> window = atLocalTime(6, 0).windowFor("breakfast", false);

		assertThat(window).contains(new Window(local(19, 7, 0), local(19, 9, 0)));
	}

	@Test
	void tomorrowOrderGetsNextDaysWindowRegardlessOfArrivalTime() {
		Optional<Window> window = atLocalTime(15, 0).windowFor("breakfast", true);

		assertThat(window).contains(new Window(local(20, 7, 0), local(20, 9, 0)));
	}

	@Test
	void sameDayOrderAfterWindowEndIsPastCutoffAndUnschedulable() {
		assertThat(atLocalTime(15, 0).windowFor("breakfast", false)).isEmpty();
		assertThat(atLocalTime(9, 0).windowFor("breakfast", false)).isEmpty(); // exactly at end
	}

	@Test
	void orderArrivingMidWindowIsStillSchedulable() {
		Optional<Window> window = atLocalTime(8, 0).windowFor("breakfast", false);

		assertThat(window).contains(new Window(local(19, 7, 0), local(19, 9, 0)));
	}

	@Test
	void lunchAndDinnerWindowsMatchSpec() {
		assertThat(atLocalTime(6, 0).windowFor("lunch", false))
				.contains(new Window(local(19, 11, 30), local(19, 13, 30)));
		assertThat(atLocalTime(6, 0).windowFor("dinner", false))
				.contains(new Window(local(19, 17, 30), local(19, 19, 30)));
	}

	@Test
	void mealNamesAreCaseInsensitive() {
		assertThat(atLocalTime(6, 0).isKnownMeal("Breakfast")).isTrue();
		assertThat(atLocalTime(6, 0).windowFor("DINNER", false)).isPresent();
	}

	@Test
	void zonedWindowFollowsItsZonesDstRules() {
		// "07:00 PST" means 7am Pacific whatever the season: PDT (UTC-7) in
		// August → 14:00Z, PST (UTC-8) in January → 15:00Z
		DispatchProperties pacific = new DispatchProperties(Map.of(
				"breakfast", new MealWindow("07:00 PST", "09:00 PST")),
				Duration.ofMinutes(30), 100);

		Instant augustMorning = Instant.parse("2026-08-19T08:00:00Z"); // 1am PDT
		DispatchWindowCalculator summer = new DispatchWindowCalculator(pacific,
				Clock.fixed(augustMorning, ZONE));
		assertThat(summer.windowFor("breakfast", false)).contains(new Window(
				Instant.parse("2026-08-19T14:00:00Z"), Instant.parse("2026-08-19T16:00:00Z")));

		Instant januaryMorning = Instant.parse("2026-01-19T09:00:00Z"); // 1am PST
		DispatchWindowCalculator winter = new DispatchWindowCalculator(pacific,
				Clock.fixed(januaryMorning, ZONE));
		assertThat(winter.windowFor("breakfast", false)).contains(new Window(
				Instant.parse("2026-01-19T15:00:00Z"), Instant.parse("2026-01-19T17:00:00Z")));
	}

	@Test
	void todayIsDeterminedInTheWindowsZoneNotUtc() {
		// 05:00Z on Aug 20 is still Aug 19, 10pm in Pacific — a same-day
		// dinner order must be judged against Aug 19's (already closed)
		// Pacific window, and tomorrow must mean Aug 20 Pacific
		DispatchProperties pacific = new DispatchProperties(Map.of(
				"dinner", new MealWindow("17:30 PST", "19:30 PST")),
				Duration.ofMinutes(30), 100);
		DispatchWindowCalculator calculator = new DispatchWindowCalculator(pacific,
				Clock.fixed(Instant.parse("2026-08-20T05:00:00Z"), ZONE));

		assertThat(calculator.windowFor("dinner", false)).isEmpty(); // Aug 19 PDT dinner is over
		assertThat(calculator.windowFor("dinner", true)).contains(new Window(
				Instant.parse("2026-08-21T00:30:00Z"), Instant.parse("2026-08-21T02:30:00Z")));
	}

	@Test
	void badWindowConfigFailsAtStartup() {
		Clock clock = Clock.fixed(Instant.parse("2026-08-19T08:00:00Z"), ZONE);
		assertThatThrownBy(() -> new DispatchWindowCalculator(new DispatchProperties(Map.of(
				"breakfast", new MealWindow("07:00 NOPE", "09:00 NOPE")),
				Duration.ofMinutes(30), 100), clock))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("breakfast");
		assertThatThrownBy(() -> new DispatchWindowCalculator(new DispatchProperties(Map.of(
				"breakfast", new MealWindow("07:00 PST", "09:00 EST")),
				Duration.ofMinutes(30), 100), clock))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("same zone");
	}

	@Test
	void unknownMealIsRejected() {
		DispatchWindowCalculator calculator = atLocalTime(6, 0);

		assertThat(calculator.isKnownMeal("brunch")).isFalse();
		assertThat(calculator.isKnownMeal(null)).isFalse();
		assertThatThrownBy(() -> calculator.windowFor("brunch", false))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
