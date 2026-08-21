package com.lab37.controller;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.lab37.model.ItemHistory;
import com.lab37.model.Order;
import com.lab37.model.OrderHistory;
import com.lab37.model.OrderItem;
import com.lab37.model.OrderStatus;
import com.lab37.model.OrderType;
import com.lab37.model.UploadJob;
import com.lab37.service.OrderProcessingService;

@RestController
@RequestMapping("/v1")
public class OrderProcessor {

	/** Same UTC minute-bucket format the dispatch counters are stored under. */
	private static final DateTimeFormatter MINUTE_BUCKET =
			DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);

	private static final int MAX_PAGE_SIZE = 200;

	private final OrderProcessingService orderProcessingService;
	private final Clock clock;

	public OrderProcessor(OrderProcessingService orderProcessingService, Clock clock) {
		this.orderProcessingService = orderProcessingService;
		this.clock = clock;
	}

	@PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<?> ingest(@RequestParam("file") MultipartFile file) throws IOException {
		String originalName = file.getOriginalFilename();
		if (file.isEmpty() || originalName == null || !originalName.toLowerCase().endsWith(".csv")) {
			return ResponseEntity.badRequest().body("Expected a non-empty .csv file");
		}
		UploadJob job = orderProcessingService.enqueueCsvUpload(file);
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(IngestResponse.of(job));
	}

	/**
	 * Webhook-style intake ("live" order stream). Acks with 200 as fast as
	 * possible: the raw payload is persisted to webhook_queue and converted
	 * into orders asynchronously — no parsing or validation happens on the
	 * request path.
	 */
	@PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Void> webhook(@RequestBody String payload) {
		orderProcessingService.enqueueWebhookPayload(payload);
		return ResponseEntity.ok().build();
	}

	@GetMapping("/jobs/{jobId}")
	public ResponseEntity<UploadJob> getJob(@PathVariable UUID jobId) {
		return orderProcessingService.getJob(jobId)
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	/**
	 * All upload jobs, newest first. Optional createdAfter (millis since
	 * epoch) narrows to jobs created at or after that time.
	 */
	@GetMapping("/jobs")
	public List<UploadJob> listJobs(
			@RequestParam(name = "createdAfter", required = false) Long createdAfter) {
		return orderProcessingService.listJobs(
				createdAfter == null ? null : Instant.ofEpochMilli(createdAfter));
	}

	/** Order details: the order plus its individual items (item status records dispatch). */
	public record OrderDetails(Order order, List<OrderItem> items) {
	}

	/** One history step: the order snapshot plus the item snapshots taken with it. */
	public record OrderHistoryEntry(OrderHistory order, List<ItemHistory> items) {
	}

	public record OrderPatch(Boolean vip) {
	}

	/**
	 * Patches an order — currently the VIP flag only ({"vip": true|false}).
	 * A VIP order jumps the dispatch queue ahead of all non-VIP orders.
	 * Allowed only while the order is CREATED (409 otherwise); 404 for an
	 * unknown order.
	 */
	@PatchMapping(value = "/orders/{orderId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> patchOrder(@PathVariable UUID orderId, @RequestBody OrderPatch patch) {
		if (patch.vip() == null) {
			return ResponseEntity.badRequest().body("vip is the only patchable field and is required");
		}
		try {
			Order order = orderProcessingService.patchOrderVip(orderId, patch.vip());
			return ResponseEntity.ok(
					new OrderDetails(order, orderProcessingService.getOrderItems(orderId)));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.notFound().build();
		} catch (IllegalStateException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
		}
	}

	@GetMapping("/orders/{orderId}")
	public ResponseEntity<OrderDetails> getOrder(@PathVariable UUID orderId) {
		return orderProcessingService.getOrder(orderId)
				.map(order -> new OrderDetails(order, orderProcessingService.getOrderItems(orderId)))
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	/**
	 * All history snapshots of the order, oldest version first, each carrying
	 * the item snapshots recorded with it — what the order's items looked
	 * like, and in what state, at that step.
	 */
	@GetMapping("/orders/{orderId}/history")
	public ResponseEntity<List<OrderHistoryEntry>> getOrderHistory(@PathVariable UUID orderId) {
		List<OrderHistory> history = orderProcessingService.getOrderHistory(orderId);
		if (history.isEmpty()) {
			// every order writes a history snapshot at creation, so an empty
			// list means the order does not exist
			return ResponseEntity.notFound().build();
		}
		Map<Integer, List<ItemHistory>> itemsByVersion =
				orderProcessingService.getItemHistory(orderId).stream()
						.collect(Collectors.groupingBy(ItemHistory::getOrderVersion));
		return ResponseEntity.ok(history.stream()
				.map(snapshot -> new OrderHistoryEntry(snapshot,
						itemsByVersion.getOrDefault(snapshot.getVersion(), List.of())))
				.toList());
	}

	/**
	 * Order search. All filters optional: status; orderId / sourceOrderId
	 * (exact matches, both indexed); createdTime OR updatedTime (millis
	 * since epoch, meaning "created/updated at or after" — passing both is
	 * a 400). Paginated via page/size, newest created first.
	 */
	@GetMapping("/orders")
	public ResponseEntity<?> searchOrders(
			@RequestParam(name = "status", required = false) String status,
			@RequestParam(name = "type", required = false) String type,
			@RequestParam(name = "orderId", required = false) String orderId,
			@RequestParam(name = "sourceOrderId", required = false) String sourceOrderId,
			@RequestParam(name = "createdTime", required = false) Long createdTime,
			@RequestParam(name = "updatedTime", required = false) Long updatedTime,
			@RequestParam(name = "page", defaultValue = "0") int page,
			@RequestParam(name = "size", defaultValue = "20") int size) {
		if (createdTime != null && updatedTime != null) {
			return ResponseEntity.badRequest()
					.body("pass either createdTime or updatedTime, not both");
		}
		OrderStatus orderStatus = null;
		if (status != null) {
			try {
				orderStatus = OrderStatus.valueOf(status.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				return ResponseEntity.badRequest().body("unknown status: " + status);
			}
		}
		OrderType orderType = null;
		if (type != null) {
			try {
				orderType = OrderType.valueOf(type.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				return ResponseEntity.badRequest().body("unknown type: " + type);
			}
		}
		UUID orderUuid = null;
		if (orderId != null && !orderId.isBlank()) {
			try {
				orderUuid = UUID.fromString(orderId.strip());
			} catch (IllegalArgumentException e) {
				return ResponseEntity.badRequest().body("orderId must be a UUID: " + orderId);
			}
		}
		if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
			return ResponseEntity.badRequest()
					.body("page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
		}
		Page<Order> result = orderProcessingService.searchOrders(orderStatus, orderType, orderUuid,
				sourceOrderId == null || sourceOrderId.isBlank() ? null : sourceOrderId.strip(),
				createdTime == null ? null : Instant.ofEpochMilli(createdTime),
				updatedTime == null ? null : Instant.ofEpochMilli(updatedTime),
				PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
		return ResponseEntity.ok(new PagedModel<>(result));
	}

	/**
	 * Per-minute counts of robot dispatches in [from, to] (inclusive, UTC
	 * minutes in yyyyMMddHHmm), oldest first. Defaults to the last hour when
	 * from/to are omitted. Sparse: minutes without dispatches are omitted.
	 */
	@GetMapping("/orders-dispatched")
	public ResponseEntity<?> ordersDispatched(
			@RequestParam(name = "from", required = false) String from,
			@RequestParam(name = "to", required = false) String to) {
		Instant now = Instant.now(clock);
		if (from == null) {
			from = MINUTE_BUCKET.format(now.minus(Duration.ofHours(1)));
		}
		if (to == null) {
			to = MINUTE_BUCKET.format(now);
		}
		if (!isValidMinute(from) || !isValidMinute(to)) {
			return ResponseEntity.badRequest()
					.body("from/to must be minutes in yyyyMMddHHmm format");
		}
		if (from.compareTo(to) > 0) {
			return ResponseEntity.badRequest().body("from must not be after to");
		}
		List<DispatchedCount> counts = orderProcessingService.dispatchedPerMinute(from, to).stream()
				.map(row -> new DispatchedCount(row.getTime(), row.getCount()))
				.toList();
		return ResponseEntity.ok(counts);
	}

	private boolean isValidMinute(String value) {
		try {
			LocalDateTime.parse(value, MINUTE_BUCKET);
			return true;
		} catch (Exception e) {
			return false;
		}
	}
}
