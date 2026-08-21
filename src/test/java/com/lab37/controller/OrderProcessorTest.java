package com.lab37.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.lab37.model.Order;
import com.lab37.model.OrdersProcessed;
import com.lab37.model.UploadFile;
import com.lab37.model.UploadJob;
import com.lab37.model.UploadJobStatus;
import com.lab37.model.WebhookQueue;
import com.lab37.model.QueueStatus;
import com.lab37.repository.OrderHistoryRepository;
import com.lab37.repository.OrderItemRepository;
import com.lab37.repository.OrderRepository;
import com.lab37.repository.OrdersProcessedRepository;
import com.lab37.repository.UploadFileRepository;
import com.lab37.repository.UploadJobRepository;
import com.lab37.repository.WebhookQueueRepository;
import com.lab37.service.CsvBatchProcessor;
import com.lab37.service.OrderProcessingService;
import com.lab37.service.RobotDispatcher;

// long poll intervals so the background workers (job processor and robot
// dispatcher) can't touch jobs/orders mid-test; tiny batch size so the
// batch/resume test only needs a handful of rows
@SpringBootTest(properties = {
		"ingest.poll-interval=1h",
		"ingest.batch-size=2",
		"dispatch.poll-interval=1h",
		"dispatch.sweep-interval=1h",
		"webhook.poll-interval=1h",
		"polling.poll-interval=1h",
		"polling.queue-poll-interval=1h"})
@AutoConfigureMockMvc
class OrderProcessorTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	OrderProcessingService orderProcessingService;

	@Autowired
	RobotDispatcher robotDispatcher;

	@Autowired
	UploadJobRepository uploadJobRepository;

	@Autowired
	UploadFileRepository uploadFileRepository;

	@Autowired
	WebhookQueueRepository webhookQueueRepository;

	@Autowired
	OrderRepository orderRepository;

	@Autowired
	OrderHistoryRepository orderHistoryRepository;

	@Autowired
	OrderItemRepository orderItemRepository;

	@Autowired
	OrdersProcessedRepository ordersProcessedRepository;

	@Autowired
	com.lab37.repository.ItemHistoryRepository itemHistoryRepository;

	@Autowired
	com.lab37.service.HistoryRecorder historyRecorder;

	@MockitoSpyBean
	CsvBatchProcessor csvBatchProcessor;

	@Autowired
	org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

	@BeforeEach
	void cleanState() {
		// the H2 database is shared by all tests in the class
		itemHistoryRepository.deleteAll();
		orderHistoryRepository.deleteAll();
		orderItemRepository.deleteAll();
		orderRepository.deleteAll();
		uploadFileRepository.deleteAll();
		uploadJobRepository.deleteAll();
		webhookQueueRepository.deleteAll();
		ordersProcessedRepository.deleteAll();
	}

	@Test
	void ingestSavesCsvAndReturnsQueuedJob() throws Exception {
		String csv = "name,item,quantity\nAlice,burger,2\n";
		MockMultipartFile file = new MockMultipartFile("file", "morning.csv", "text/csv", csv.getBytes());

		String body = mockMvc.perform(multipart("/v1/ingest").file(file))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.jobId").exists())
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andReturn().getResponse().getContentAsString();

		String jobId = JsonPath.read(body, "$.jobId");
		String selfLink = JsonPath.read(body, "$.links.self");
		assertThat(selfLink).isEqualTo("/v1/jobs/" + jobId);

		List<UploadFile> saved = uploadFileRepository.findAll();
		assertThat(saved).hasSize(1);
		assertThat(saved.get(0).getJobId().toString()).isEqualTo(jobId);
		assertThat(new String(saved.get(0).getContent())).isEqualTo(csv);
	}

	@Test
	void jobEndpointReturnsUploadJobRow() throws Exception {
		MockMultipartFile file =
				new MockMultipartFile("file", "noon.csv", "text/csv", "a,b\n1,2\n".getBytes());

		String body = mockMvc.perform(multipart("/v1/ingest").file(file))
				.andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();
		String selfLink = JsonPath.read(body, "$.links.self");
		String jobId = JsonPath.read(body, "$.jobId");

		mockMvc.perform(get(selfLink))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(jobId))
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.attempts").value(0))
				.andExpect(jsonPath("$.error").doesNotExist())
				.andExpect(jsonPath("$.fileName").value("noon.csv"))
				.andExpect(jsonPath("$.createdAt").exists())
				.andExpect(jsonPath("$.startedAt").doesNotExist())
				.andExpect(jsonPath("$.finishedAt").doesNotExist());
	}

	@Test
	void jobsListingReturnsAllJobsNewestFirstAndFiltersByCreatedAfter() throws Exception {
		UploadJob older = uploadJobRepository.save(UploadJob.queued("a.csv"));
		UploadJob newer = uploadJobRepository.save(UploadJob.queued("b.csv"));
		long farFuture = java.time.Instant.now().plusSeconds(3600).toEpochMilli();

		mockMvc.perform(get("/v1/jobs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[?(@.id == '%s')]".formatted(older.getId())).exists())
				.andExpect(jsonPath("$[?(@.id == '%s')]".formatted(newer.getId())).exists());

		mockMvc.perform(get("/v1/jobs").param("createdAfter", "0"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2));

		mockMvc.perform(get("/v1/jobs").param("createdAfter", String.valueOf(farFuture)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void jobEndpointReturns404ForUnknownJob() throws Exception {
		mockMvc.perform(get("/v1/jobs/" + UUID.randomUUID()))
				.andExpect(status().isNotFound());
	}

	@Test
	void malformedJobIdStaysClientErrorDespiteCatchAllHandler() throws Exception {
		// the global Exception handler must not swallow Spring's 4xx mapping
		mockMvc.perform(get("/v1/jobs/not-a-uuid"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void processingResumesFromByteOffsetAfterMidFileFailure() throws Exception {
		// header + 5 rows; batch size is 2, so this takes 3 batches
		String csv = """
				first_name,last_name,items,notes,tomorrow,meal
				Alice,Smith,burger,none,false,lunch
				Bob,Jones,salad,none,true,dinner
				Carol,White,pizza,none,false,dinner
				Dave,Brown,tacos,none,false,lunch
				Eve,Black,soup,none,true,lunch
				""";
		MockMultipartFile file = new MockMultipartFile("file", "big.csv", "text/csv", csv.getBytes());
		String body = mockMvc.perform(multipart("/v1/ingest").file(file))
				.andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();
		UUID jobId = UUID.fromString(JsonPath.read(body, "$.jobId"));

		// batch 1 succeeds and commits its byte offset; batch 2 blows up
		doCallRealMethod()
				.doThrow(new RuntimeException("simulated crash mid-file"))
				.when(csvBatchProcessor).processNextBatch(any(UploadJob.class));

		orderProcessingService.processQueuedJob();

		UploadJob failed = uploadJobRepository.findById(jobId).orElseThrow();
		assertThat(failed.getStatus()).isEqualTo(UploadJobStatus.FAILED);
		assertThat(failed.getError()).contains("simulated crash");
		assertThat(failed.getByteOffset()).isGreaterThan(0);
		assertThat(orderRepository.findByJobId(jobId)).hasSize(2); // only batch 1 committed

		// "next run": failure cause is gone; requeue the job
		reset(csvBatchProcessor);
		failed.setStatus(UploadJobStatus.QUEUED);
		uploadJobRepository.save(failed);

		orderProcessingService.processQueuedJob();

		UploadJob done = uploadJobRepository.findById(jobId).orElseThrow();
		assertThat(done.getStatus()).isEqualTo(UploadJobStatus.DONE);
		assertThat(done.getAttempts()).isEqualTo(2);
		List<Order> orders = orderRepository.findByJobId(jobId);
		assertThat(orders).hasSize(5); // resumed rows 3-5, and rows 1-2 were NOT duplicated
		assertThat(orders).extracting(order -> order.getFirstName())
				.containsExactlyInAnyOrder("Alice", "Bob", "Carol", "Dave", "Eve");
		assertThat(orders).allSatisfy(order -> {
			assertThat(order.getVersion()).isEqualTo(1);
			assertThat(order.getJobId()).isEqualTo(jobId);
		});
	}

	@Test
	void webhookEndpointAcksAndQueuesRawPayload() throws Exception {
		// shaped like webhook_orders.jsonl records
		String payload = """
				{"order_id": "8fb6d085-95b8-4923-bd82-0f5ed9c823ed", "order_source": "Overeats", \
				"restaurant": "Sam & Ella's", "first_name": "Laura", "last_name": "Kinney", \
				"total": 144.16, "items": ["Lemon meringue pie", "Chicken fried steak"], "notes": ""}""";

		mockMvc.perform(post("/v1/webhook")
						.contentType(MediaType.APPLICATION_JSON)
						.content(payload))
				.andExpect(status().isOk());

		List<WebhookQueue> queued = webhookQueueRepository.findAll();
		assertThat(queued).hasSize(1);
		WebhookQueue entry = queued.get(0);
		assertThat(entry.getStatus()).isEqualTo(QueueStatus.RECEIVED);
		assertThat(entry.getRetryCount()).isZero();
		assertThat(entry.getCreatedOn()).isNotNull();
		assertThat(entry.getPayload()).isEqualTo(payload); // stored verbatim
		// nothing processed synchronously — orders appear only via the async consumer
		assertThat(orderRepository.findAll()).isEmpty();
	}

	@Test
	void webhookPayloadBecomesOrderWithItemsAndHistory() throws Exception {
		String payload = """
				{"order_id": "5820055e-41a6-44a7-bb93-621a7fb5ccd8", "order_source": "Overeats", \
				"restaurant": "Bread Pitt", "first_name": "Kate", "last_name": "Bishop", \
				"total": 99.78, "items": ["Espresso", "Croissant", "Avocado toast"], "notes": ""}""";
		mockMvc.perform(post("/v1/webhook").contentType(MediaType.APPLICATION_JSON).content(payload))
				.andExpect(status().isOk());

		orderProcessingService.processWebHookRequests();

		List<Order> orders = orderRepository.findAll();
		assertThat(orders).hasSize(1);
		Order order = orders.get(0);
		assertThat(order.getExternalOrderId())
				.isEqualTo("5820055e-41a6-44a7-bb93-621a7fb5ccd8");
		assertThat(order.getOrderType()).isEqualTo(com.lab37.model.OrderType.WEBHOOK);
		assertThat(order.getOrderStatus()).isEqualTo(com.lab37.model.OrderStatus.CREATED);
		assertThat(order.getAmount()).isEqualByComparingTo("99.78");
		assertThat(order.getOrderSource()).isEqualTo("Overeats");
		assertThat(order.getRestaurant()).isEqualTo("Bread Pitt");
		assertThat(order.getJobId()).isNull(); // no upload job for webhook orders
		assertThat(orderItemRepository.findByOrderId(order.getId()))
				.extracting(item -> item.getItemName())
				.containsExactlyInAnyOrder("Espresso", "Croissant", "Avocado toast");
		assertThat(orderHistoryRepository.findByOrderIdOrderByVersion(order.getId())).hasSize(1);
		assertThat(webhookQueueRepository.findAll().get(0).getStatus())
				.isEqualTo(QueueStatus.PROCESSED);

		// a record reusing the order_id is a content update while CREATED:
		// customer changed their order before dispatch
		String updated = """
				{"order_id": "5820055e-41a6-44a7-bb93-621a7fb5ccd8", "order_source": "Overeats", \
				"restaurant": "Bread Pitt", "first_name": "Kate", "last_name": "Bishop", \
				"total": 111.50, "items": ["Espresso", "Bagel"], "notes": "extra shot"}""";
		mockMvc.perform(post("/v1/webhook").contentType(MediaType.APPLICATION_JSON).content(updated))
				.andExpect(status().isOk());
		orderProcessingService.processWebHookRequests();

		assertThat(orderRepository.findAll()).hasSize(1); // updated in place, not duplicated
		Order afterUpdate = orderRepository.findById(order.getId()).orElseThrow();
		assertThat(afterUpdate.getAmount()).isEqualByComparingTo("111.50");
		assertThat(afterUpdate.getNotes()).isEqualTo("extra shot");
		assertThat(afterUpdate.getVersion()).isEqualTo(2);
		assertThat(orderItemRepository.findByOrderId(order.getId()))
				.extracting(item -> item.getItemName())
				.containsExactlyInAnyOrder("Espresso", "Bagel"); // items replaced
		assertThat(orderHistoryRepository.findByOrderIdOrderByVersion(order.getId())).hasSize(2);

		// cancellation update reusing the order_id cancels the existing order
		String cancel = """
				{"order_id": "5820055e-41a6-44a7-bb93-621a7fb5ccd8", "order_source": "Overeats", \
				"restaurant": "Bread Pitt", "first_name": "Kate", "last_name": "Bishop", \
				"total": 111.50, "items": ["Espresso"], "notes": "", "update": ["cancelled"]}""";
		mockMvc.perform(post("/v1/webhook").contentType(MediaType.APPLICATION_JSON).content(cancel))
				.andExpect(status().isOk());
		orderProcessingService.processWebHookRequests();

		Order cancelled = orderRepository.findById(order.getId()).orElseThrow();
		assertThat(cancelled.getOrderStatus()).isEqualTo(com.lab37.model.OrderStatus.CANCELLED);
		assertThat(cancelled.getVersion()).isEqualTo(3);
		assertThat(orderHistoryRepository.findByOrderIdOrderByVersion(order.getId())).hasSize(3);
	}

	@Test
	void webhookUpdateAfterDispatchIsRefused() throws Exception {
		String payload = """
				{"order_id": "7bf5c958-c394-4adb-9d32-8414fdfd5a9c", "order_source": "DoorDrop", \
				"restaurant": "Just Pizza", "first_name": "Ellie", "last_name": "Phimister", \
				"total": 40.28, "items": ["Veggie with hummus"], "notes": ""}""";
		mockMvc.perform(post("/v1/webhook").contentType(MediaType.APPLICATION_JSON).content(payload))
				.andExpect(status().isOk());
		orderProcessingService.processWebHookRequests();
		Order order = orderRepository.findAll().get(0);

		robotDispatcher.dispatch(order.getId()); // robot is already making the food

		String updated = """
				{"order_id": "7bf5c958-c394-4adb-9d32-8414fdfd5a9c", "order_source": "DoorDrop", \
				"restaurant": "Just Pizza", "first_name": "Ellie", "last_name": "Phimister", \
				"total": 99.99, "items": ["Sausage and onion"], "notes": ""}""";
		mockMvc.perform(post("/v1/webhook").contentType(MediaType.APPLICATION_JSON).content(updated))
				.andExpect(status().isOk());
		orderProcessingService.processWebHookRequests();

		Order after = orderRepository.findById(order.getId()).orElseThrow();
		assertThat(after.getOrderStatus()).isEqualTo(com.lab37.model.OrderStatus.DISPATCHED);
		assertThat(after.getAmount()).isEqualByComparingTo("40.28"); // update refused
		assertThat(after.getVersion()).isEqualTo(2); // only the dispatch bump
		assertThat(orderItemRepository.findByOrderId(order.getId()))
				.extracting(item -> item.getItemName())
				.containsExactly("Veggie with hummus");
	}

	@Test
	void oversizedWebhookOrderIsSavedAsErrorNotCreated() throws Exception {
		String hugeItem = "x".repeat(1025);
		String payload = """
				{"order_id": "9e107d9d-3720-4bcb-a6a3-1fb1ec33d7a2", "order_source": "Overeats", \
				"restaurant": "Bread Pitt", "first_name": "Kate", "last_name": "Bishop", \
				"total": 10.00, "items": ["%s"], "notes": ""}""".formatted(hugeItem);
		mockMvc.perform(post("/v1/webhook").contentType(MediaType.APPLICATION_JSON).content(payload))
				.andExpect(status().isOk());

		orderProcessingService.processWebHookRequests();

		Order order = orderRepository
				.findByExternalOrderId("9e107d9d-3720-4bcb-a6a3-1fb1ec33d7a2").orElseThrow();
		assertThat(order.getOrderStatus()).isEqualTo(com.lab37.model.OrderStatus.ERROR);
		assertThat(order.getError()).isEqualTo("order length exceeds maximum limit");
	}

	@Test
	void ordersDispatchedReturnsPerMinuteCountsInRangeOldestFirst() throws Exception {
		for (String minute : List.of("202608201210", "202608201213", "202608201212", "202608201305")) {
			OrdersProcessed row = OrdersProcessed.forMinute(minute);
			row.increment();
			ordersProcessedRepository.save(row);
		}

		mockMvc.perform(get("/v1/orders-dispatched")
						.param("from", "202608201211").param("to", "202608201213"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].minute").value("202608201212"))
				.andExpect(jsonPath("$[0].count").value(1))
				.andExpect(jsonPath("$[1].minute").value("202608201213"));
	}

	@Test
	void ordersDispatchedWithoutParamsDefaultsToLastHourUtc() throws Exception {
		java.time.format.DateTimeFormatter bucket = java.time.format.DateTimeFormatter
				.ofPattern("yyyyMMddHHmm").withZone(java.time.ZoneOffset.UTC);
		java.time.Instant now = java.time.Instant.now();
		String recent = bucket.format(now.minus(java.time.Duration.ofMinutes(2)));
		String stale = bucket.format(now.minus(java.time.Duration.ofHours(3)));
		for (String minute : List.of(recent, stale)) {
			OrdersProcessed row = OrdersProcessed.forMinute(minute);
			row.increment();
			ordersProcessedRepository.save(row);
		}

		mockMvc.perform(get("/v1/orders-dispatched"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].minute").value(recent));
	}

	@Test
	void ordersDispatchedRejectsMalformedOrInvertedRange() throws Exception {
		mockMvc.perform(get("/v1/orders-dispatched")
						.param("from", "2026-08-20 12:10").param("to", "202608201213"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get("/v1/orders-dispatched")
						.param("from", "202608201213").param("to", "202608201210"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void getOrderReturnsOrderWithItemsOr404() throws Exception {
		Order order = orderRepository.save(Order.fromCsv(
				UUID.randomUUID(), "Alice", "Smith", "burger, fries", "", false, "lunch"));
		orderItemRepository.save(com.lab37.model.OrderItem.of(order.getId(), "burger", null));
		com.lab37.model.OrderItem cancelled =
				com.lab37.model.OrderItem.of(order.getId(), "fries", null);
		cancelled.setSourceStatus("cancelled");
		orderItemRepository.save(cancelled);

		mockMvc.perform(get("/v1/orders/" + order.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.order.id").value(order.getId().toString()))
				.andExpect(jsonPath("$.order.firstName").value("Alice"))
				.andExpect(jsonPath("$.order.orderStatus").value("CREATED"))
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[?(@.itemName == 'fries')].sourceStatus").value("cancelled"));

		mockMvc.perform(get("/v1/orders/" + UUID.randomUUID()))
				.andExpect(status().isNotFound());
	}

	@Test
	void dispatchFlipsSentItemsToDispatchedAndLeavesHeldOnesCreated() throws Exception {
		Order order = orderRepository.save(Order.fromCsv(
				UUID.randomUUID(), "Alice", "Smith", "soup, pie", "", false, "lunch"));
		orderItemRepository.save(com.lab37.model.OrderItem.of(order.getId(), "soup", null));
		orderItemRepository.save(com.lab37.model.OrderItem.fromApi(
				order.getId(), "pie", null, "aa11", "processing")); // source already making it

		robotDispatcher.dispatch(order.getId());

		mockMvc.perform(get("/v1/orders/" + order.getId()))
				.andExpect(status().isOk())
				// one item held back → the order is PARTIALLY_DISPATCHED
				.andExpect(jsonPath("$.order.orderStatus").value("PARTIALLY_DISPATCHED"))
				.andExpect(jsonPath("$.items[?(@.itemName == 'soup')].status").value("DISPATCHED"))
				.andExpect(jsonPath("$.items[?(@.itemName == 'pie')].status").value("CREATED"));

		// the dispatch-version history snapshot records the same split
		mockMvc.perform(get("/v1/orders/" + order.getId() + "/history"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].items[?(@.itemName == 'soup')].status").value("DISPATCHED"))
				.andExpect(jsonPath("$[0].items[?(@.itemName == 'pie')].status").value("CREATED"));
	}

	@Test
	void getOrderHistoryReturnsSnapshotsWithItemStatesPerVersionOr404() throws Exception {
		Order order = orderRepository.save(Order.fromCsv(
				UUID.randomUUID(), "Alice", "Smith", "burger", "", false, "lunch"));
		com.lab37.model.OrderItem item =
				com.lab37.model.OrderItem.of(order.getId(), "burger", null);
		orderItemRepository.save(item);
		historyRecorder.snapshot(order); // version 1: no source status yet
		order.setOrderStatus(com.lab37.model.OrderStatus.DISPATCHED);
		// flush so Hibernate's @Version increment lands before the snapshot
		order = orderRepository.saveAndFlush(order);
		item.setSourceStatus("cancelled");
		orderItemRepository.save(item);
		historyRecorder.snapshot(order); // version 2: item cancelled at the source

		mockMvc.perform(get("/v1/orders/" + order.getId() + "/history"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].order.version").value(1))
				.andExpect(jsonPath("$[0].items.length()").value(1))
				.andExpect(jsonPath("$[0].items[0].itemName").value("burger"))
				.andExpect(jsonPath("$[0].items[0].sourceStatus").doesNotExist()) // nulls are omitted
				.andExpect(jsonPath("$[1].order.version").value(2))
				.andExpect(jsonPath("$[1].order.orderStatus").value("DISPATCHED"))
				.andExpect(jsonPath("$[1].items[0].sourceStatus").value("cancelled"));

		mockMvc.perform(get("/v1/orders/" + UUID.randomUUID() + "/history"))
				.andExpect(status().isNotFound());
	}

	@Test
	void patchMakesOrderVipWithVersionBumpAndHistorySnapshot() throws Exception {
		Order order = orderRepository.save(Order.fromCsv(
				UUID.randomUUID(), "Alice", "Smith", "burger", "", false, "lunch"));
		historyRecorder.snapshot(order); // version 1

		mockMvc.perform(patch("/v1/orders/" + order.getId())
						.contentType(MediaType.APPLICATION_JSON).content("{\"vip\": true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.order.vip").value(true))
				.andExpect(jsonPath("$.order.version").value(2));

		mockMvc.perform(get("/v1/orders/" + order.getId() + "/history"))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].order.vip").value(false))
				.andExpect(jsonPath("$[1].order.vip").value(true));

		// patching to the value it already has is a no-op: no version bump
		mockMvc.perform(patch("/v1/orders/" + order.getId())
						.contentType(MediaType.APPLICATION_JSON).content("{\"vip\": true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.order.version").value(2));
	}

	@Test
	void patchRejectsUnknownOrderNonCreatedStateAndMissingVip() throws Exception {
		mockMvc.perform(patch("/v1/orders/" + UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON).content("{\"vip\": true}"))
				.andExpect(status().isNotFound());

		Order dispatched = Order.fromCsv(
				UUID.randomUUID(), "Bob", "Jones", "salad", "", false, "lunch");
		dispatched.setOrderStatus(com.lab37.model.OrderStatus.DISPATCHED);
		orderRepository.save(dispatched);
		mockMvc.perform(patch("/v1/orders/" + dispatched.getId())
						.contentType(MediaType.APPLICATION_JSON).content("{\"vip\": true}"))
				.andExpect(status().isConflict());

		Order created = orderRepository.save(Order.fromCsv(
				UUID.randomUUID(), "Carol", "White", "pizza", "", false, "lunch"));
		mockMvc.perform(patch("/v1/orders/" + created.getId())
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void searchOrdersFiltersByStatusAndPaginates() throws Exception {
		Order created = orderRepository.save(Order.fromCsv(
				UUID.randomUUID(), "Alice", "Smith", "burger", "", false, "lunch"));
		Order dispatched = Order.fromCsv(
				UUID.randomUUID(), "Bob", "Jones", "salad", "", false, "lunch");
		dispatched.setOrderStatus(com.lab37.model.OrderStatus.DISPATCHED);
		orderRepository.save(dispatched);

		mockMvc.perform(get("/v1/orders").param("status", "dispatched"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].id").value(dispatched.getId().toString()));

		mockMvc.perform(get("/v1/orders").param("size", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.page.totalElements").value(2))
				.andExpect(jsonPath("$.page.totalPages").value(2));

		// unknown status value is rejected, not silently unmatched
		mockMvc.perform(get("/v1/orders").param("status", "nope"))
				.andExpect(status().isBadRequest());
		assertThat(created.getId()).isNotNull();
	}

	@Test
	void searchOrdersFiltersByType() throws Exception {
		orderRepository.save(Order.fromCsv(
				UUID.randomUUID(), "Alice", "Smith", "burger", "", false, "lunch"));
		Order webhook = orderRepository.save(Order.fromWebhook(
				"ext-1", "Overeats", "Bread Pitt", "Kate", "Bishop",
				new java.math.BigDecimal("10.00"), "Espresso", ""));

		mockMvc.perform(get("/v1/orders").param("type", "webhook"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].id").value(webhook.getId().toString()));

		mockMvc.perform(get("/v1/orders").param("type", "SVC_FILE"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1));

		mockMvc.perform(get("/v1/orders").param("type", "nope"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void searchOrdersFindsByOrderIdAndSourceOrderId() throws Exception {
		Order csv = orderRepository.save(Order.fromCsv(
				UUID.randomUUID(), "Alice", "Smith", "burger", "", false, "lunch"));
		Order webhook = orderRepository.save(Order.fromWebhook(
				"ext-4651", "Overeats", "Bread Pitt", "Kate", "Bishop",
				new java.math.BigDecimal("10.00"), "Espresso", ""));

		mockMvc.perform(get("/v1/orders").param("orderId", csv.getId().toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].id").value(csv.getId().toString()));

		mockMvc.perform(get("/v1/orders").param("sourceOrderId", "ext-4651"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].id").value(webhook.getId().toString()));

		mockMvc.perform(get("/v1/orders").param("sourceOrderId", "nope"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(0));

		mockMvc.perform(get("/v1/orders").param("orderId", "not-a-uuid"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void searchOrdersFiltersByTimeButRejectsBothTimeParams() throws Exception {
		orderRepository.save(Order.fromCsv(
				UUID.randomUUID(), "Alice", "Smith", "burger", "", false, "lunch"));
		long farFuture = java.time.Instant.now().plusSeconds(3600).toEpochMilli();

		mockMvc.perform(get("/v1/orders").param("createdTime", "0"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1));
		mockMvc.perform(get("/v1/orders").param("createdTime", String.valueOf(farFuture)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(0));
		mockMvc.perform(get("/v1/orders").param("updatedTime", "0"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1));

		mockMvc.perform(get("/v1/orders")
						.param("createdTime", "0").param("updatedTime", "0"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void dispatchLoopRunsUnderShedLock() {
		// invoking through the Spring proxy must acquire the DB scheduler
		// lock, leaving a row in the shedlock table
		orderProcessingService.dispatchDueOrders();

		Integer locks = jdbcTemplate.queryForObject(
				"select count(*) from shedlock where name = 'dispatchDueOrders'", Integer.class);
		assertThat(locks).isEqualTo(1);
	}

	@Test
	void ingestRejectsNonCsvFile() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "orders.json", "application/json", "{}".getBytes());

		mockMvc.perform(multipart("/v1/ingest").file(file))
				.andExpect(status().isBadRequest());

		assertThat(uploadFileRepository.findAll()).isEmpty();
	}

	@Test
	void ingestRejectsEmptyFile() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "empty.csv", "text/csv", new byte[0]);

		mockMvc.perform(multipart("/v1/ingest").file(file))
				.andExpect(status().isBadRequest());

		assertThat(uploadFileRepository.findAll()).isEmpty();
	}
}
