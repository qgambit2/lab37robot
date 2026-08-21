package com.lab37.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Limit;

import com.lab37.model.Order;
import com.lab37.model.OrderStatus;

@DataJpaTest
class OrderRepositoryTest {

	@Autowired
	OrderRepository repository;

	private Order csvOrder(String firstName, Instant windowStart, Instant windowEnd) {
		Order order = Order.fromCsv(UUID.randomUUID(), firstName, "Test", "burger", "", false, "lunch");
		order.setDispatchTimeInterval(windowStart, windowEnd);
		return repository.save(order);
	}

	@Test
	void findDispatchableReturnsOldestCreatedOrdersInsideTheirStoredWindow() {
		Instant now = Instant.now();
		Instant expiryCutoff = now.minus(Duration.ofMinutes(30));

		Order inWindowFirst = csvOrder("First", now.minusSeconds(600), now.plusSeconds(600));
		Order inWindowSecond = csvOrder("Second", now.minusSeconds(600), now.plusSeconds(600));
		csvOrder("TooEarly", now.plusSeconds(600), now.plusSeconds(1200)); // window not open yet
		csvOrder("TooLate", now.minusSeconds(1200), now.minusSeconds(600)); // window closed
		Order dispatched = csvOrder("AlreadySent", now.minusSeconds(600), now.plusSeconds(600));
		dispatched.setOrderStatus(OrderStatus.DISPATCHED);
		repository.save(dispatched);

		List<Order> due = repository.findDispatchable(now, expiryCutoff, Limit.of(10));

		assertThat(due).extracting(Order::getId)
				.containsExactly(inWindowFirst.getId(), inWindowSecond.getId());
	}

	@Test
	void itemsAndNotesColumnsHoldMoreThan255Chars() {
		String longItems = "Milkshakes (vanilla, chocolate, strawberry), ".repeat(10); // ~450 chars
		String longNotes = "extra sauce on the side please, ".repeat(10);
		Order order = Order.fromCsv(UUID.randomUUID(), "Alice", "Test",
				longItems, longNotes, false, "lunch");

		Order saved = repository.saveAndFlush(order);

		assertThat(saved.getItems()).isEqualTo(longItems);
		assertThat(saved.getNotes()).isEqualTo(longNotes);
	}

	@Test
	void itemsAndNotesBeyondColumnSizeAreTruncatedNotRejected() {
		String oversized = "x".repeat(Order.TEXT_MAX_LENGTH + 100);
		Order order = Order.fromCsv(UUID.randomUUID(), "Alice", "Test",
				oversized, oversized, false, "lunch");

		Order saved = repository.saveAndFlush(order);

		assertThat(saved.getItems()).hasSize(Order.TEXT_MAX_LENGTH);
		assertThat(saved.getNotes()).hasSize(Order.TEXT_MAX_LENGTH);
	}

	@Test
	void findDispatchableReturnsVipOrdersBeforeOlderNonVipOnes() {
		Instant now = Instant.now();
		Order oldest = csvOrder("Oldest", now.minusSeconds(600), now.plusSeconds(600));
		Order middle = csvOrder("Middle", now.minusSeconds(600), now.plusSeconds(600));
		Order newestVip = csvOrder("NewestVip", now.minusSeconds(600), now.plusSeconds(600));
		newestVip.setVip(true);
		repository.save(newestVip);

		List<Order> due = repository.findDispatchable(now, now.minus(Duration.ofMinutes(30)),
				Limit.of(10));

		// the VIP order jumps the queue despite being the newest
		assertThat(due).extracting(Order::getId)
				.containsExactly(newestVip.getId(), oldest.getId(), middle.getId());
	}

	@Test
	void vipMakesAnOrderDueImmediatelyEvenOutsideItsMealWindow() {
		Instant now = Instant.now();
		// window opens tomorrow — not due; VIP overrides that
		Order tomorrowVip = csvOrder("TomorrowVip",
				now.plusSeconds(86_400), now.plusSeconds(93_600));
		tomorrowVip.setVip(true);
		repository.save(tomorrowVip);
		csvOrder("TomorrowPlain", now.plusSeconds(86_400), now.plusSeconds(93_600));

		List<Order> due = repository.findDispatchable(now, now.minus(Duration.ofMinutes(30)),
				Limit.of(10));

		assertThat(due).extracting(Order::getId).containsExactly(tomorrowVip.getId());
	}

	@Test
	void findStaleReturnsExactlyTheOrdersTheDispatcherWillNeverPickUp() {
		Instant now = Instant.now();
		Instant cutoff = now.minus(Duration.ofMinutes(30));
		// stale: CSV window closed
		Order pastWindow = csvOrder("PastWindow", now.minusSeconds(1200), now.minusSeconds(600));
		// not stale: CSV window still open
		csvOrder("InWindow", now.minusSeconds(600), now.plusSeconds(600));
		// stale CSV but VIP → due immediately, never swept
		Order vipPastWindow = csvOrder("VipPastWindow", now.minusSeconds(1200), now.minusSeconds(600));
		vipPastWindow.setVip(true);
		repository.save(vipPastWindow);
		// immediate order aged past the freshness horizon
		Order staleWebhook = Order.fromWebhook("stale-1", "Overeats", "Bread Pitt",
				"Kate", "Bishop", null, "Espresso", "");
		staleWebhook.setUpdatedAt(now.minus(Duration.ofMinutes(31)));
		repository.save(staleWebhook);
		// immediate order still fresh
		Order freshWebhook = Order.fromWebhook("fresh-1", "Overeats", "Bread Pitt",
				"Kate", "Bishop", null, "Latte", "");
		repository.save(freshWebhook);

		List<Order> stale = repository.findStaleForUpdate(now, cutoff);

		assertThat(stale).extracting(Order::getId)
				.containsExactlyInAnyOrder(pastWindow.getId(), staleWebhook.getId());
	}

	@Test
	void findDispatchableHonorsTheLimit() {
		Instant now = Instant.now();
		for (int i = 0; i < 5; i++) {
			csvOrder("Order" + i, now.minusSeconds(600), now.plusSeconds(600));
		}

		List<Order> due = repository.findDispatchable(now, now.minus(Duration.ofMinutes(30)), Limit.of(3));

		assertThat(due).hasSize(3);
	}
}
