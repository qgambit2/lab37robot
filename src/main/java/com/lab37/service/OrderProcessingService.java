package com.lab37.service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.lab37.model.ItemHistory;
import com.lab37.model.Order;
import com.lab37.model.OrderHistory;
import com.lab37.model.OrderItem;
import com.lab37.model.OrderStatus;
import com.lab37.model.OrderType;
import com.lab37.model.OrdersProcessed;
import com.lab37.model.UploadFile;
import com.lab37.model.UploadJob;
import com.lab37.model.UploadJobStatus;
import com.lab37.model.WebhookQueue;
import com.lab37.model.QueueStatus;
import com.lab37.repository.ItemHistoryRepository;
import com.lab37.repository.OrderHistoryRepository;
import com.lab37.repository.OrderItemRepository;
import com.lab37.repository.OrderRepository;
import com.lab37.repository.OrdersProcessedRepository;
import com.lab37.repository.UploadFileRepository;
import com.lab37.repository.UploadJobRepository;
import com.lab37.repository.WebhookQueueRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderProcessingService {

	private static final Logger log = LoggerFactory.getLogger(OrderProcessingService.class);

	/** Dispatch-count buckets are UTC minutes, regardless of the clock's zone. */
	private static final DateTimeFormatter MINUTE_BUCKET =
			DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);

	private final UploadJobRepository uploadJobRepository;
	private final UploadFileRepository uploadFileRepository;
	private final WebhookQueueRepository webhookQueueRepository;
	private final CsvBatchProcessor csvBatchProcessor;
	private final WebhookOrderProcessor webhookOrderProcessor;
	private final OrderRepository orderRepository;
	private final OrderItemRepository orderItemRepository;
	private final OrderHistoryRepository orderHistoryRepository;
	private final ItemHistoryRepository itemHistoryRepository;
	private final HistoryRecorder historyRecorder;
	private final OrdersProcessedRepository ordersProcessedRepository;
	private final RobotDispatcher robotDispatcher;
	private final DispatchProperties dispatchProperties;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final boolean postgresClaim;
	private final int webhookMaxRetries;

	public OrderProcessingService(UploadJobRepository uploadJobRepository,
			UploadFileRepository uploadFileRepository,
			WebhookQueueRepository webhookQueueRepository,
			CsvBatchProcessor csvBatchProcessor,
			WebhookOrderProcessor webhookOrderProcessor,
			OrderRepository orderRepository,
			OrderItemRepository orderItemRepository,
			OrderHistoryRepository orderHistoryRepository,
			ItemHistoryRepository itemHistoryRepository,
			HistoryRecorder historyRecorder,
			OrdersProcessedRepository ordersProcessedRepository,
			RobotDispatcher robotDispatcher,
			DispatchProperties dispatchProperties,
			ObjectMapper objectMapper,
			Clock clock,
			@Value("${ingest.claim-dialect:h2}") String claimDialect,
			@Value("${webhook.max-retries:5}") int webhookMaxRetries) {
		this.uploadJobRepository = uploadJobRepository;
		this.uploadFileRepository = uploadFileRepository;
		this.webhookQueueRepository = webhookQueueRepository;
		this.csvBatchProcessor = csvBatchProcessor;
		this.webhookOrderProcessor = webhookOrderProcessor;
		this.orderRepository = orderRepository;
		this.orderItemRepository = orderItemRepository;
		this.orderHistoryRepository = orderHistoryRepository;
		this.itemHistoryRepository = itemHistoryRepository;
		this.historyRecorder = historyRecorder;
		this.ordersProcessedRepository = ordersProcessedRepository;
		this.robotDispatcher = robotDispatcher;
		this.dispatchProperties = dispatchProperties;
		this.objectMapper = objectMapper;
		this.clock = clock;
		this.postgresClaim = "postgres".equalsIgnoreCase(claimDialect);
		this.webhookMaxRetries = webhookMaxRetries;
	}

	/**
	 * Stores the uploaded CSV's bytes in the DB (shared across app instances)
	 * and records a QUEUED upload job for it — atomically, so a job can never
	 * exist without its file content.
	 */
	@Transactional
	public UploadJob enqueueCsvUpload(MultipartFile file) throws IOException {
		// getFileName() strips any client-supplied directory components
		String fileName = Path.of(file.getOriginalFilename()).getFileName().toString();
		UploadJob job = uploadJobRepository.save(UploadJob.queued(fileName));
		uploadFileRepository.save(UploadFile.of(job.getId(), file.getBytes()));
		return job;
	}

	public Optional<UploadJob> getJob(UUID jobId) {
		return uploadJobRepository.findById(jobId);
	}

	/** All upload jobs, newest first; createdAfter (nullable) means "created at or after". */
	public List<UploadJob> listJobs(Instant createdAfter) {
		return uploadJobRepository.search(createdAfter);
	}

	public Optional<Order> getOrder(UUID orderId) {
		return orderRepository.findById(orderId);
	}

	/** The order's current items (the source of truth for what the robot would make). */
	public List<OrderItem> getOrderItems(UUID orderId) {
		return orderItemRepository.findByOrderId(orderId);
	}

	/** All history snapshots of an order, oldest version first; empty for an unknown order. */
	public List<OrderHistory> getOrderHistory(UUID orderId) {
		return orderHistoryRepository.findByOrderIdOrderByVersion(orderId);
	}

	/** All item snapshots of an order across versions, oldest version first. */
	public List<ItemHistory> getItemHistory(UUID orderId) {
		return itemHistoryRepository.findByOrderIdOrderByOrderVersion(orderId);
	}


	/**
	 * Flips an order's VIP flag — the queue-jump lever for an order that must
	 * not wait behind a busy dispatch queue. Allowed only while the order is
	 * still CREATED; a no-op change (already at the requested value) bumps
	 * nothing.
	 *
	 * @throws IllegalArgumentException if no such order exists
	 * @throws IllegalStateException if the order is not in CREATED state
	 */
	@Transactional
	public Order patchOrderVip(UUID orderId, boolean vip) {
		// row-locked read: serializes with the dispatcher, so an order can't
		// be picked up for dispatch mid-patch (and vice versa)
		Order order = orderRepository.findByIdForUpdate(orderId)
				.orElseThrow(() -> new IllegalArgumentException("No order with id " + orderId));
		if (order.getOrderStatus() != OrderStatus.CREATED) {
			throw new IllegalStateException("Order " + orderId + " cannot be changed: status is "
					+ order.getOrderStatus() + ", changes are only allowed while CREATED");
		}
		if (order.isVip() == vip) {
			return order; // no-op — no version bump, no history spam
		}
		order.setVip(vip);
		order.setUpdatedAt(Instant.now(clock));
		// flush so Hibernate's @Version increment lands before we snapshot
		orderRepository.saveAndFlush(order);
		historyRecorder.snapshot(order);
		log.info("Order {} vip set to {} (now version {})", orderId, vip, order.getVersion());
		return order;
	}

	/**
	 * Order search backing GET /v1/orders — every filter optional (ids are
	 * exact matches; both are indexed), time filters mean "at or after".
	 * Callers pass at most one of createdAfter/updatedAfter.
	 */
	public Page<Order> searchOrders(OrderStatus status, OrderType orderType, UUID orderId,
			String externalOrderId, Instant createdAfter, Instant updatedAfter, Pageable pageable) {
		return orderRepository.search(status, orderType, orderId, externalOrderId,
				createdAfter, updatedAfter, pageable);
	}

	/**
	 * Persists a webhook payload verbatim so the endpoint can ack fast; the
	 * async consumer (scheduled job, to come) converts it into orders later.
	 */
	public WebhookQueue enqueueWebhookPayload(String payload) {
		return webhookQueueRepository.save(WebhookQueue.received(payload, Instant.now(clock)));
	}

	/**
	 * Async webhook consumer: turns queued payloads into orders, oldest
	 * first. Entries past the processing deadline or with unparseable
	 * payloads go terminally to PROCESSING_FAILURE (retrying can't fix
	 * either); transient failures (e.g. DB errors) go to ERROR and are
	 * retried until webhook.max-retries. Each payload's inserts (order +
	 * items + history) are a single transaction in WebhookOrderProcessor.
	 */
	@Scheduled(fixedDelayString = "${webhook.poll-interval}")
	public void processWebHookRequests() {
		List<WebhookQueue> entries = webhookQueueRepository.findProcessable(webhookMaxRetries);
		Instant now = Instant.now(clock);
		Instant deadline = now.minus(dispatchProperties.immediateCancelAfter());
		for (WebhookQueue entry : entries) {
			if (entry.getCreatedOn().isBefore(deadline)) {
				entry.setStatus(QueueStatus.PROCESSING_FAILURE);
				entry.setError("expired: received " + entry.getCreatedOn() + ", not processed within "
						+ dispatchProperties.immediateCancelAfter());
				log.warn("Webhook entry {} PROCESSING_FAILURE — {}", entry.getId(), entry.getError());
				webhookQueueRepository.save(entry);
				continue;
			}
			WebhookOrder order;
			try {
				order = objectMapper.readValue(entry.getPayload(), WebhookOrder.class);
				if (order.orderId() == null) {
					throw new IllegalArgumentException("payload has no order_id");
				}
			} catch (Exception e) {
				// a payload that can't be parsed today can't be parsed
				// tomorrow either — terminal, no retry
				entry.setStatus(QueueStatus.PROCESSING_FAILURE);
				entry.setError(truncate("unparseable payload: " + e.getMessage(), 255));
				log.warn("Webhook entry {} PROCESSING_FAILURE — {}", entry.getId(), entry.getError());
				webhookQueueRepository.save(entry);
				continue;
			}
			try {
				webhookOrderProcessor.process(order);
				entry.setStatus(QueueStatus.PROCESSED);
				entry.setError(null);
			} catch (Exception e) {
				entry.setStatus(QueueStatus.ERROR);
				entry.setRetryCount(entry.getRetryCount() + 1);
				entry.setError(truncate(e.toString(), 255));
				log.error("Webhook entry {} failed (attempt {}/{})", entry.getId(),
						entry.getRetryCount(), webhookMaxRetries, e);
			}
			webhookQueueRepository.save(entry);
		}
	}

	/**
	 * DB-as-queue worker. The claim is a single atomic statement (UPDATE +
	 * SKIP LOCKED subselect) that flips the row to RUNNING and returns it, so
	 * no wrapping transaction is needed here — once claimed, the row is
	 * invisible to other pollers by status alone.
	 */
	@Scheduled(fixedDelayString = "${ingest.poll-interval}")
	public void processQueuedJob() {
		// the atomic claim statement is DB-specific (H2: FINAL TABLE,
		// Postgres: UPDATE … RETURNING) — selected by ingest.claim-dialect
		long nowMillis = Instant.now(clock).toEpochMilli();
		Optional<UploadJob> next = postgresClaim
				? uploadJobRepository.claimNextQueuedJobPostgres(nowMillis)
				: uploadJobRepository.claimNextQueuedJob(nowMillis);
		if (next.isEmpty()) {
			return;
		}
		UploadJob job = next.get();
		try {
			// each iteration commits one batch of orders + the advanced byte_offset;
			// a failure resumes from the last committed offset on the next run
			while (csvBatchProcessor.processNextBatch(job)) {
				// keep going until the file is exhausted
			}
			job.setStatus(UploadJobStatus.DONE);
		} catch (Exception e) {
			// ERROR + full stack trace: this is the signal a log aggregator
			// (Splunk etc.) alerts on for failed job processing
			log.error("Failed processing upload job {} (file: {}, byte offset: {})",
					job.getId(), job.getFileName(), job.getByteOffset(), e);
			job.setStatus(UploadJobStatus.FAILED);
			job.setError(truncate(e.toString(), 255));
		}
		job.setFinishedAt(Instant.now());
		uploadJobRepository.save(job);
	}

	/**
	 * Robot dispatch loop. Every tick it computes the remaining dispatch
	 * budget from a sliding one-minute window (current minute's count plus
	 * the previous minute's count weighted by how much of it still overlaps
	 * the window — this smooths minute boundaries so a burst at :59 followed
	 * by a burst at :01 can't flood the robot), then dispatches that many of
	 * eligible orders — VIP first, then oldest — incrementing the orders_processed counter
	 * for each. At the limit it logs a warning and defers to the next tick.
	 */
	@Scheduled(fixedDelayString = "${dispatch.poll-interval}")
	// single dispatcher across all app instances: the tick runs only on the
	// instance holding the DB lock — both dispatch races (rate-limit budget,
	// duplicate order selection) are prevented by there being one dispatcher
	@SchedulerLock(name = "dispatchDueOrders", lockAtMostFor = "5m")
	public void dispatchDueOrders() {
		// UTC-anchored so minute buckets and the sliding window are UTC-based
		ZonedDateTime now = ZonedDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
		int budget = remainingDispatchBudget(now);
		if (budget <= 0) {
			log.warn("Robot dispatch rate limit ({}/min) reached — deferring dispatches to a later tick",
					dispatchProperties.maxPerMinute());
			return;
		}
		Instant nowInstant = now.toInstant();
		Instant expiryCutoff = nowInstant.minus(dispatchProperties.immediateCancelAfter());
		List<Order> due = orderRepository.findDispatchable(nowInstant, expiryCutoff, Limit.of(budget));
		for (Order order : due) {
			try {
				// null = closed without a robot request (nothing left to
				// make) — doesn't count against the robot rate limit
				if (robotDispatcher.dispatch(order.getId()) != null) {
					incrementDispatchCount(now);
				}
			} catch (Exception e) {
				log.error("Failed dispatching order {} to robot", order.getId(), e);
			}
		}
	}

	private int remainingDispatchBudget(ZonedDateTime now) {
		int current = dispatchCountFor(now);
		int previous = dispatchCountFor(now.minusMinutes(1));
		// the fraction of the sliding window still covered by the previous minute
		double previousWeight = (60 - now.getSecond()) / 60.0;
		int effective = current + (int) Math.ceil(previous * previousWeight);
		return dispatchProperties.maxPerMinute() - effective;
	}

	private int dispatchCountFor(ZonedDateTime minute) {
		return ordersProcessedRepository.findById(MINUTE_BUCKET.format(minute))
				.map(OrdersProcessed::getCount)
				.orElse(0);
	}

	private void incrementDispatchCount(ZonedDateTime now) {
		String bucket = MINUTE_BUCKET.format(now);
		OrdersProcessed row = ordersProcessedRepository.findById(bucket)
				.orElseGet(() -> OrdersProcessed.forMinute(bucket));
		row.increment();
		ordersProcessedRepository.save(row);
	}

	/**
	 * Per-minute robot dispatch counts for buckets in [from, to] (inclusive,
	 * yyyyMMddHHmm), oldest first. Sparse: minutes without dispatches have no
	 * row and are omitted.
	 */
	public List<OrdersProcessed> dispatchedPerMinute(String from, String to) {
		return ordersProcessedRepository.findByTimeBetweenOrderByTimeAsc(from, to);
	}

	private String truncate(String value, int maxLength) {
		return value.length() <= maxLength ? value : value.substring(0, maxLength);
	}
}
