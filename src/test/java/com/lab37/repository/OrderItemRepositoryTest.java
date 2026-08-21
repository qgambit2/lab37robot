package com.lab37.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.lab37.model.OrderItem;

@DataJpaTest
class OrderItemRepositoryTest {

	@Autowired
	OrderItemRepository repository;

	@Test
	void savesAndFindsItemsByOrderId() {
		UUID orderId = UUID.randomUUID();
		repository.save(OrderItem.of(orderId, "Avocado toast", null));
		repository.save(OrderItem.of(orderId, "Cappuccino", null));
		repository.save(OrderItem.of(UUID.randomUUID(), "Other order's dish", null));

		List<OrderItem> items = repository.findByOrderId(orderId);

		assertThat(items).hasSize(2);
		assertThat(items).extracting(OrderItem::getItemName)
				.containsExactlyInAnyOrder("Avocado toast", "Cappuccino");
		assertThat(items).allSatisfy(item -> {
			assertThat(item.getOrderId()).isEqualTo(orderId);
			assertThat(item.getItemPrice()).isNull();
		});
	}

	@Test
	void longItemNamesFitAndOversizedOnesAreTruncatedNotRejected() {
		UUID orderId = UUID.randomUUID();
		String longName = "Two eggs any style with bacon or sausage, hash browns, and toast ".repeat(5);
		String oversized = "x".repeat(OrderItem.NAME_MAX_LENGTH + 50);
		repository.saveAndFlush(OrderItem.of(orderId, longName, null));
		repository.saveAndFlush(OrderItem.of(orderId, oversized, null));

		List<OrderItem> items = repository.findByOrderId(orderId);

		assertThat(items).extracting(OrderItem::getItemName)
				.containsExactlyInAnyOrder(longName, "x".repeat(OrderItem.NAME_MAX_LENGTH));
	}
}
