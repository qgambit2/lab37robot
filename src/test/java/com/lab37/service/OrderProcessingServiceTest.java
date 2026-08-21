package com.lab37.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;
import org.springframework.mock.web.MockMultipartFile;

import com.lab37.model.Order;
import com.lab37.model.OrdersProcessed;
import com.lab37.model.UploadFile;
import com.lab37.model.UploadJob;
import com.lab37.model.UploadJobStatus;
import com.lab37.model.WebhookQueue;
import com.lab37.model.QueueStatus;
import com.lab37.repository.OrderRepository;
import com.lab37.repository.OrdersProcessedRepository;
import com.lab37.repository.UploadFileRepository;
import com.lab37.repository.UploadJobRepository;
import com.lab37.repository.WebhookQueueRepository;

class OrderProcessingServiceTest {

	private static final ZoneId ZONE = ZoneOffset.UTC;
	// second-of-minute = 30, so the previous minute carries weight 0.5
	private static final ZonedDateTime NOW =
			ZonedDateTime.of(2026, 8, 19, 12, 0, 30, 0, ZONE);

	@TempDir
	Path tempDir;

	UploadJobRepository repository;
	UploadFileRepository uploadFileRepository;
	WebhookQueueRepository webhookQueueRepository;
	CsvBatchProcessor batchProcessor;
	WebhookOrderProcessor webhookOrderProcessor;
	OrderRepository orderRepository;
	OrdersProcessedRepository ordersProcessedRepository;
	RobotDispatcher robotDispatcher;
	OrderProcessingService service;

	@BeforeEach
	void setUp() {
		repository = mock(UploadJobRepository.class);
		uploadFileRepository = mock(UploadFileRepository.class);
		webhookQueueRepository = mock(WebhookQueueRepository.class);
		batchProcessor = mock(CsvBatchProcessor.class);
		webhookOrderProcessor = mock(WebhookOrderProcessor.class);
		orderRepository = mock(OrderRepository.class);
		ordersProcessedRepository = mock(OrdersProcessedRepository.class);
		robotDispatcher = mock(RobotDispatcher.class);
		when(repository.save(any(UploadJob.class))).thenAnswer(inv -> inv.getArgument(0));
		DispatchProperties properties = new DispatchProperties(Map.of(),
				Duration.ofMinutes(30), 10);
		service = new OrderProcessingService(repository, uploadFileRepository,
				webhookQueueRepository, batchProcessor, webhookOrderProcessor, orderRepository,
				mock(com.lab37.repository.OrderItemRepository.class),
				mock(com.lab37.repository.OrderHistoryRepository.class),
				mock(com.lab37.repository.ItemHistoryRepository.class),
				mock(HistoryRecorder.class),
				ordersProcessedRepository, robotDispatcher, properties,
				new tools.jackson.databind.json.JsonMapper(),
				Clock.fixed(NOW.toInstant(), ZONE), "h2", 5);
	}

	@Test
	void enqueueCsvUploadStoresContentInDbAndQueuesJob() throws Exception {
		String csv = "name,item,quantity\nAlice,burger,2\n";
		MockMultipartFile file = new MockMultipartFile("file", "morning.csv", "text/csv", csv.getBytes());

		UploadJob job = service.enqueueCsvUpload(file);

		assertThat(job.getId()).isNotNull();
		assertThat(job.getStatus()).isEqualTo(UploadJobStatus.QUEUED);
		assertThat(job.getAttempts()).isZero();
		assertThat(job.getError()).isNull();
		assertThat(job.getByteOffset()).isZero();
		assertThat(job.getCreatedAt()).isNotNull();
		assertThat(job.getStartedAt()).isNull();
		assertThat(job.getFinishedAt()).isNull();
		assertThat(job.getFileName()).isEqualTo("morning.csv");
		verify(repository).save(job);
		ArgumentCaptor<UploadFile> fileCaptor = ArgumentCaptor.forClass(UploadFile.class);
		verify(uploadFileRepository).save(fileCaptor.capture());
		assertThat(fileCaptor.getValue().getJobId()).isEqualTo(job.getId());
		assertThat(new String(fileCaptor.getValue().getContent())).isEqualTo(csv);
	}

	@Test
	void enqueueCsvUploadStripsDirectoryComponentsFromFilename() throws Exception {
		MockMultipartFile file =
				new MockMultipartFile("file", "../../etc/orders.csv", "text/csv", "a,b\n".getBytes());

		UploadJob job = service.enqueueCsvUpload(file);

		assertThat(job.getFileName()).isEqualTo("orders.csv");
	}

	@Test
	void enqueueWebhookPayloadStoresVerbatimAsReceived() {
		when(webhookQueueRepository.save(any(WebhookQueue.class)))
				.thenAnswer(inv -> inv.getArgument(0));
		String payload = "{\"order_id\": \"abc\", \"total\": 12.5}";

		WebhookQueue entry = service.enqueueWebhookPayload(payload);

		assertThat(entry.getPayload()).isEqualTo(payload);
		assertThat(entry.getStatus()).isEqualTo(QueueStatus.RECEIVED);
		assertThat(entry.getRetryCount()).isZero();
		assertThat(entry.getCreatedOn()).isEqualTo(NOW.toInstant());
		verify(webhookQueueRepository).save(entry);
	}

	private static final String VALID_WEBHOOK_PAYLOAD = """
			{"order_id": "c59a0083-581d-4eb9-946e-98b32890be3a", "order_source": "Overeats", \
			"restaurant": "Sam & Ella's", "first_name": "Laura", "last_name": "Kinney", \
			"total": 144.16, "items": ["Lemon meringue pie"], "notes": ""}""";

	@Test
	void processWebHookRequestsConvertsPayloadAndMarksProcessed() {
		WebhookQueue entry = WebhookQueue.received(VALID_WEBHOOK_PAYLOAD, NOW.toInstant());
		when(webhookQueueRepository.findProcessable(5)).thenReturn(List.of(entry));

		service.processWebHookRequests();

		ArgumentCaptor<WebhookOrder> captor = ArgumentCaptor.forClass(WebhookOrder.class);
		verify(webhookOrderProcessor).process(captor.capture());
		assertThat(captor.getValue().orderId())
				.isEqualTo(UUID.fromString("c59a0083-581d-4eb9-946e-98b32890be3a"));
		assertThat(captor.getValue().total()).isEqualByComparingTo("144.16");
		assertThat(entry.getStatus()).isEqualTo(QueueStatus.PROCESSED);
		verify(webhookQueueRepository).save(entry);
	}

	@Test
	void processWebHookRequestsExpiresEntriesPastDeadlineWithoutRetry() {
		WebhookQueue stale = WebhookQueue.received(VALID_WEBHOOK_PAYLOAD,
				NOW.toInstant().minus(Duration.ofMinutes(31)));
		when(webhookQueueRepository.findProcessable(5)).thenReturn(List.of(stale));

		service.processWebHookRequests();

		assertThat(stale.getStatus()).isEqualTo(QueueStatus.PROCESSING_FAILURE);
		assertThat(stale.getError()).contains("expired");
		assertThat(stale.getRetryCount()).isZero(); // terminal, not a retry
		verify(webhookOrderProcessor, never()).process(any());
	}

	@Test
	void processWebHookRequestsMarksUnparseablePayloadTerminalFailure() {
		WebhookQueue garbage = WebhookQueue.received("this is not json", NOW.toInstant());
		WebhookQueue noId = WebhookQueue.received("{\"total\": 5}", NOW.toInstant());
		when(webhookQueueRepository.findProcessable(5)).thenReturn(List.of(garbage, noId));

		service.processWebHookRequests();

		assertThat(garbage.getStatus()).isEqualTo(QueueStatus.PROCESSING_FAILURE);
		assertThat(garbage.getError()).contains("unparseable");
		assertThat(noId.getStatus()).isEqualTo(QueueStatus.PROCESSING_FAILURE);
		assertThat(noId.getError()).contains("order_id");
		verify(webhookOrderProcessor, never()).process(any());
	}

	@Test
	void processWebHookRequestsRetriesTransientFailures() {
		WebhookQueue entry = WebhookQueue.received(VALID_WEBHOOK_PAYLOAD, NOW.toInstant());
		when(webhookQueueRepository.findProcessable(5)).thenReturn(List.of(entry));
		org.mockito.Mockito.doThrow(new RuntimeException("connection reset"))
				.when(webhookOrderProcessor).process(any());

		service.processWebHookRequests();

		assertThat(entry.getStatus()).isEqualTo(QueueStatus.ERROR);
		assertThat(entry.getRetryCount()).isEqualTo(1);
		assertThat(entry.getError()).contains("connection reset");
		verify(webhookQueueRepository).save(entry);
	}

	/** Mimics the state the atomic claim query leaves a row in. */
	private static UploadJob claimedJob(String filePath) {
		UploadJob job = UploadJob.queued(filePath);
		job.setStatus(UploadJobStatus.RUNNING);
		job.setLockedAt(Instant.now());
		job.setStartedAt(Instant.now());
		job.setAttempts(1);
		return job;
	}

	@Test
	void processQueuedJobRunsBatchesUntilFileExhaustedThenMarksDone() throws Exception {
		UploadJob job = claimedJob(tempDir.resolve("noon.csv").toString());
		when(repository.claimNextQueuedJob(NOW.toInstant().toEpochMilli())).thenReturn(Optional.of(job));
		when(batchProcessor.processNextBatch(job)).thenReturn(true, true, false);

		service.processQueuedJob();

		verify(batchProcessor, times(3)).processNextBatch(job);
		assertThat(job.getStatus()).isEqualTo(UploadJobStatus.DONE);
		assertThat(job.getFinishedAt()).isNotNull();
		assertThat(job.getError()).isNull();
		verify(repository).save(job);
	}

	@Test
	void processQueuedJobMarksJobFailedWhenBatchThrows() throws Exception {
		UploadJob job = claimedJob(tempDir.resolve("missing.csv").toString());
		when(repository.claimNextQueuedJob(NOW.toInstant().toEpochMilli())).thenReturn(Optional.of(job));
		when(batchProcessor.processNextBatch(job)).thenThrow(new IOException("disk exploded"));

		service.processQueuedJob();

		assertThat(job.getStatus()).isEqualTo(UploadJobStatus.FAILED);
		assertThat(job.getError()).contains("disk exploded");
		assertThat(job.getFinishedAt()).isNotNull();
		verify(repository).save(job);
	}

	@Test
	void processQueuedJobDoesNothingWhenQueueIsEmpty() throws Exception {
		when(repository.claimNextQueuedJob(NOW.toInstant().toEpochMilli())).thenReturn(Optional.empty());

		service.processQueuedJob();

		verify(batchProcessor, never()).processNextBatch(any());
		verify(repository, never()).save(any());
	}

	@Test
	void dispatchDueOrdersSendsOldestEligibleOrdersWithinBudget() {
		Order first = Order.fromCsv(
				UUID.randomUUID(), "Alice", "Smith", "burger", "", false, "lunch");
		Order second = Order.fromCsv(
				UUID.randomUUID(), "Bob", "Jones", "salad", "", false, "lunch");
		when(ordersProcessedRepository.findById(any())).thenReturn(Optional.empty());
		when(orderRepository.findDispatchable(any(), any(), any()))
				.thenReturn(List.of(first, second));
		when(robotDispatcher.dispatch(any()))
				.thenReturn(new RobotDispatchRequest(first.getId(),
						List.of(new RobotDispatchRequest.Item(UUID.randomUUID(), "burger"))));

		service.dispatchDueOrders();

		// full budget of 10: both orders dispatched, counter bumped twice
		ArgumentCaptor<Limit> limitCaptor =
				ArgumentCaptor.forClass(Limit.class);
		verify(orderRepository).findDispatchable(
				eq(NOW.toInstant()),
				eq(NOW.toInstant().minus(Duration.ofMinutes(30))),
				limitCaptor.capture());
		assertThat(limitCaptor.getValue().max()).isEqualTo(10);
		verify(robotDispatcher).dispatch(first.getId());
		verify(robotDispatcher).dispatch(second.getId());
		verify(ordersProcessedRepository, times(2))
				.save(any(OrdersProcessed.class));
	}

	@Test
	void ordersClosedWithoutRobotRequestDoNotConsumeDispatchBudget() {
		Order order = Order.fromCsv(
				UUID.randomUUID(), "Alice", "Smith", "burger", "", false, "lunch");
		when(ordersProcessedRepository.findById(any())).thenReturn(Optional.empty());
		when(orderRepository.findDispatchable(any(), any(), any())).thenReturn(List.of(order));
		when(robotDispatcher.dispatch(order.getId())).thenReturn(null); // nothing left to make

		service.dispatchDueOrders();

		verify(ordersProcessedRepository, never()).save(any());
	}

	@Test
	void dispatchDueOrdersUsesSlidingWindowOverPreviousMinute() {
		// current minute: 3 dispatched; previous minute: 10, weighted 0.5 at
		// second 30 → effective 8, budget 10-8 = 2
		OrdersProcessed current = OrdersProcessed.forMinute("202608191200");
		for (int i = 0; i < 3; i++) current.increment();
		OrdersProcessed previous = OrdersProcessed.forMinute("202608191159");
		for (int i = 0; i < 10; i++) previous.increment();
		when(ordersProcessedRepository.findById("202608191200")).thenReturn(Optional.of(current));
		when(ordersProcessedRepository.findById("202608191159")).thenReturn(Optional.of(previous));
		when(orderRepository.findDispatchable(any(), any(), any())).thenReturn(List.of());

		service.dispatchDueOrders();

		ArgumentCaptor<Limit> limitCaptor =
				ArgumentCaptor.forClass(Limit.class);
		verify(orderRepository).findDispatchable(any(), any(), limitCaptor.capture());
		assertThat(limitCaptor.getValue().max()).isEqualTo(2);
	}

	@Test
	void dispatchDueOrdersStopsAndWarnsWhenRateLimitReached() {
		OrdersProcessed current = OrdersProcessed.forMinute("202608191200");
		for (int i = 0; i < 10; i++) current.increment();
		when(ordersProcessedRepository.findById("202608191200")).thenReturn(Optional.of(current));
		when(ordersProcessedRepository.findById("202608191159")).thenReturn(Optional.empty());

		service.dispatchDueOrders();

		verify(orderRepository, never()).findDispatchable(any(), any(), any());
		verify(robotDispatcher, never()).dispatch(any());
	}

	@Test
	void dispatchDueOrdersContinuesAfterSingleOrderFailure() {
		Order first = Order.fromCsv(
				UUID.randomUUID(), "Alice", "Smith", "burger", "", false, "lunch");
		Order second = Order.fromCsv(
				UUID.randomUUID(), "Bob", "Jones", "salad", "", false, "lunch");
		when(ordersProcessedRepository.findById(any())).thenReturn(Optional.empty());
		when(orderRepository.findDispatchable(any(), any(), any()))
				.thenReturn(List.of(first, second));
		when(robotDispatcher.dispatch(first.getId()))
				.thenThrow(new IllegalStateException("status changed concurrently"));

		service.dispatchDueOrders();

		verify(robotDispatcher).dispatch(second.getId());
	}

	@Test
	void getJobDelegatesToRepository() {
		UploadJob job = UploadJob.queued("x.csv");
		when(repository.findById(job.getId())).thenReturn(Optional.of(job));

		assertThat(service.getJob(job.getId())).contains(job);
		assertThat(service.getJob(UUID.randomUUID())).isEmpty();
	}
}
