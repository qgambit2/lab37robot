package com.lab37.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.lab37.model.ApiOrderQueue;
import com.lab37.model.ApiPolling;
import com.lab37.model.QueueStatus;
import com.lab37.repository.ApiOrderQueueRepository;
import com.lab37.repository.ApiPollingRepository;
import com.lab37.service.ApiPollResponse.ApiPollItem;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import tools.jackson.databind.ObjectMapper;

/**
 * Polls the external orders API for deltas (time_since query parameter) and
 * enqueues each usable response as ONE api_order_queue row holding the raw
 * body — a single atomic insert, so a response can never be half-enqueued
 * (per-order enqueueing could leave duplicates behind when a later save
 * fails and the window is re-polled). The async consumer does the parsing,
 * per-order grouping, and processing, with entry-level retries — safe
 * because re-applying an already-applied order's delta is a no-op.
 *
 * The time_since cursor is persisted in the single-row api_polling table
 * (shared across instances, restart-safe) and advances only after the
 * response row is durably saved. Polling runs under ShedLock so only one
 * instance polls at a time.
 */
@Service
public class PollingApiPoller {

	private static final Logger log = LoggerFactory.getLogger(PollingApiPoller.class);

	private final RestClient restClient;
	private final ApiOrderProcessor apiOrderProcessor;
	private final ApiOrderQueueRepository apiOrderQueueRepository;
	private final ApiPollingRepository apiPollingRepository;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final String endpoint;
	private final int maxRetries;
	private final boolean logResponses;

	public PollingApiPoller(RestClient.Builder restClientBuilder,
			ApiOrderProcessor apiOrderProcessor,
			ApiOrderQueueRepository apiOrderQueueRepository,
			ApiPollingRepository apiPollingRepository,
			ObjectMapper objectMapper,
			Clock clock,
			@Value("${polling.endpoint}") String endpoint,
			@Value("${polling.max-retries:5}") int maxRetries,
			@Value("${polling.log-responses:true}") boolean logResponses) {
		this.restClient = restClientBuilder.build();
		this.apiOrderProcessor = apiOrderProcessor;
		this.apiOrderQueueRepository = apiOrderQueueRepository;
		this.apiPollingRepository = apiPollingRepository;
		this.objectMapper = objectMapper;
		this.clock = clock;
		this.endpoint = endpoint;
		this.maxRetries = maxRetries;
		this.logResponses = logResponses;
	}

	@Scheduled(fixedDelayString = "${polling.poll-interval}")
	@SchedulerLock(name = "pollApiOrders", lockAtMostFor = "5m")
	public void pollApiOrders() {
		Instant polledAt = Instant.now(clock);
		String body;
		try {
			body = restClient.get()
					.uri(endpoint + "?time_since=" + currentTimeSince())
					.retrieve()
					.body(String.class);
		} catch (Exception e) {
			log.error("Polling API call to {} failed", endpoint, e);
			return;
		}
		if (logResponses) {
			// visibility that data is actually arriving; disable via
			// polling.log-responses=false when log volume matters
			log.info("Polling API response ({} chars): {}",
					body == null ? 0 : body.length(), body);
		}
		try {
			if (enqueueResponse(body)) {
				advanceCursor(polledAt);
			}
		} catch (Exception e) {
			// e.g. the enqueue insert failed — cursor stays put, window re-polled
			log.error("Failed handling polling API response", e);
		}
	}

	/** The persisted time_since cursor (millis since epoch); 0 when we have never polled. */
	long currentTimeSince() {
		return apiPollingRepository.findById(ApiPolling.SINGLETON_ID)
				.map(ApiPolling::getLastPolled)
				.orElse(0L);
	}

	void advanceCursor(Instant polledAt) {
		ApiPolling row = apiPollingRepository.findById(ApiPolling.SINGLETON_ID)
				.orElseGet(() -> ApiPolling.of(0));
		row.setLastPolled(polledAt.toEpochMilli());
		apiPollingRepository.save(row);
	}

	/**
	 * Validates the response just enough to decide cursor advancement (a 500
	 * or unparseable body must be re-polled) and stores a usable response as
	 * ONE queue row containing the raw body. Returns true when the cursor may
	 * advance.
	 */
	boolean enqueueResponse(String body) {
		ApiPollResponse response;
		try {
			response = objectMapper.readValue(body, ApiPollResponse.class);
		} catch (Exception e) {
			log.error("Unparseable polling API response: {}", truncate(body), e);
			return false;
		}
		if (response.response() != 200) {
			log.error("Polling API returned {}{}", response.response(),
					response.error() != null ? ": " + response.error() : "");
			return false;
		}
		if (response.data() == null || response.data().isEmpty()) {
			return true; // no changes since time_since — nothing to enqueue
		}
		apiOrderQueueRepository.save(ApiOrderQueue.received(body, Instant.now(clock)));
		return true;
	}

	/**
	 * Async consumer of the api_order_queue: parses each stored response
	 * body, groups its item entries by order (a response routinely mixes
	 * items of many orders), and applies one order per transaction via
	 * ApiOrderProcessor. On partial failure the entry goes to ERROR with its
	 * payload REWRITTEN to only the failed orders' items, so a retry (up to
	 * polling.max-retries) replays nothing that already succeeded; per-order
	 * idempotency remains the backstop for a crash between applying orders
	 * and saving the shrunken entry. No expiry deadline here: unlike webhook
	 * orders these deltas include status updates for orders already in
	 * flight, which stay worth applying late.
	 */
	@Scheduled(fixedDelayString = "${polling.queue-poll-interval}")
	public void processApiOrderQueue() {
		List<ApiOrderQueue> entries = apiOrderQueueRepository.findProcessable(maxRetries);
		for (ApiOrderQueue entry : entries) {
			ApiPollResponse response;
			try {
				response = objectMapper.readValue(entry.getPayload(), ApiPollResponse.class);
			} catch (Exception e) {
				entry.setStatus(QueueStatus.PROCESSING_FAILURE);
				entry.setError(truncate("unparseable payload: " + e.getMessage()));
				log.warn("API order queue entry {} PROCESSING_FAILURE — {}", entry.getId(), entry.getError());
				apiOrderQueueRepository.save(entry);
				continue;
			}
			Map<String, Map<String, ApiPollItem>> byOrder = groupByOrder(response);
			List<String> failedOrders = new ArrayList<>();
			Map<String, ApiPollItem> failedItems = new HashMap<>();
			Exception lastFailure = null;
			for (Map.Entry<String, Map<String, ApiPollItem>> orderEntry : byOrder.entrySet()) {
				try {
					apiOrderProcessor.process(orderEntry.getKey(), orderEntry.getValue());
				} catch (Exception e) {
					log.error("Failed applying polling delta for order {}", orderEntry.getKey(), e);
					failedOrders.add(orderEntry.getKey());
					failedItems.putAll(orderEntry.getValue());
					lastFailure = e;
				}
			}
			if (failedOrders.isEmpty()) {
				entry.setStatus(QueueStatus.PROCESSED);
				entry.setError(null);
			} else {
				// shrink the payload to just the failed orders' items so the
				// retry replays nothing that already succeeded (idempotency
				// remains the backstop for a crash before this save commits)
				entry.setPayload(objectMapper.writeValueAsString(
						new ApiPollResponse(200, null, failedItems)));
				entry.setStatus(QueueStatus.ERROR);
				entry.setRetryCount(entry.getRetryCount() + 1);
				entry.setError(truncate("orders " + failedOrders + ": " + lastFailure));
				log.error("API order queue entry {} failed for orders {} (attempt {}/{})",
						entry.getId(), failedOrders, entry.getRetryCount(), maxRetries);
			}
			apiOrderQueueRepository.save(entry);
		}
	}

	private Map<String, Map<String, ApiPollItem>> groupByOrder(ApiPollResponse response) {
		Map<String, Map<String, ApiPollItem>> byOrder = new HashMap<>();
		if (response.data() == null) {
			return byOrder;
		}
		for (Map.Entry<String, ApiPollItem> entry : response.data().entrySet()) {
			// numeric source order ids are carried as strings in our schema
			String externalOrderId = String.valueOf(entry.getValue().order());
			byOrder.computeIfAbsent(externalOrderId, key -> new HashMap<>())
					.put(entry.getKey(), entry.getValue());
		}
		return byOrder;
	}

	private String truncate(String value) {
		return value == null ? "null" : value.length() <= 200 ? value : value.substring(0, 200) + "…";
	}
}
