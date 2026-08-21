package com.lab37.controller;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * In-app home of all demo traffic mocks, under /mocks. Sample data lives in
 * src/main/resources/order-sources (loaded as Spring resources, so config
 * may point at classpath: or file: locations). Everything is driven through
 * one control endpoint, POST /mocks/controls, whose JSON body may combine:
 *
 * <ul>
 * <li>{@code "pollingApiEnabled": true|false} — whether the mock polling API
 * serves data (initially false: always an empty delta);</li>
 * <li>{@code "sendWebhookTraffic": true} — replay every event of the webhook
 * sample file against the real webhook intake endpoint, as fast as a small
 * thread pool allows (blocks until done, reports sent/failed counts);</li>
 * <li>{@code "uploadFile": "orders_1.csv"} — POST the named CSV from the
 * order-sources folder to the real ingest endpoint, as if a user uploaded
 * it.</li>
 * </ul>
 *
 * <p><b>Polling API replay semantics</b> ({@code GET /mocks/orders}): the
 * poller targets its own port, so this endpoint is what it hits. Enabled, it
 * replays the sample file one line per poll: a request with a HIGHER
 * time_since than the previous request advances to the next line; the SAME
 * time_since returns the same line again — mirroring real delta semantics,
 * where an unadvanced cursor re-reads the same window. Scripted error lines
 * (non-200) are the exception: they are served once, and a re-poll of the
 * same window advances past them — a real API's transient failure clears on
 * retry, whereas replaying the error deterministically would trap the poller
 * (which correctly refuses to advance its cursor on an error) in an infinite
 * loop. The response set doesn't grow over time like a real system's would;
 * fine for the demo.
 */
@RestController
@RequestMapping("/mocks")
public class MockController {

	private static final Logger log = LoggerFactory.getLogger(MockController.class);

	private static final String EMPTY_RESPONSE = "{\"response\": 200, \"data\": {}}";

	private static final Pattern RESPONSE_200 = Pattern.compile("\"response\"\\s*:\\s*200");

	private static final int WEBHOOK_SEND_THREADS = 10;

	private final String pollingDataFile;
	private final String webhookDataFile;
	private final String webhookEndpoint;
	private final String ingestEndpoint;
	private final String orderSourcesDir;
	private final RestClient restClient;
	private final ResourceLoader resourceLoader;

	private volatile boolean pollingApiEnabled;
	private List<String> lines;
	private Long lastTimeSince;
	private int index;
	private boolean exhaustionLogged;

	public MockController(RestClient.Builder restClientBuilder,
			ResourceLoader resourceLoader,
			@Value("${polling.mock-enabled:false}") boolean initiallyEnabled,
			@Value("${polling.mock-data-file:classpath:order-sources/api_responses.jsonl}") String pollingDataFile,
			@Value("${webhook.mock-data-file:classpath:order-sources/webhook_orders.jsonl}") String webhookDataFile,
			@Value("${webhook.endpoint}") String webhookEndpoint,
			@Value("${ingest.endpoint:http://localhost:8081/v1/ingest}") String ingestEndpoint,
			@Value("${ingest.mock-data-dir:classpath:order-sources/}") String orderSourcesDir) {
		this.restClient = restClientBuilder.build();
		this.resourceLoader = resourceLoader;
		this.pollingApiEnabled = initiallyEnabled;
		this.pollingDataFile = pollingDataFile;
		this.webhookDataFile = webhookDataFile;
		this.webhookEndpoint = webhookEndpoint;
		this.ingestEndpoint = ingestEndpoint;
		this.orderSourcesDir = orderSourcesDir;
	}

	public record MockControlRequest(Boolean pollingApiEnabled, Boolean sendWebhookTraffic,
			String uploadFile) {
	}

	@PostMapping(value = "/controls", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> controls(@RequestBody MockControlRequest request) {
		if (request.pollingApiEnabled() == null && request.sendWebhookTraffic() == null
				&& request.uploadFile() == null) {
			return ResponseEntity.badRequest().body(Map.of("error",
					"provide at least one of pollingApiEnabled, sendWebhookTraffic, uploadFile"));
		}
		Map<String, Object> result = new LinkedHashMap<>();
		if (request.pollingApiEnabled() != null) {
			pollingApiEnabled = request.pollingApiEnabled();
			log.info("Mock polling API {}", pollingApiEnabled ? "enabled" : "disabled");
		}
		result.put("pollingApiEnabled", pollingApiEnabled);
		if (Boolean.TRUE.equals(request.sendWebhookTraffic())) {
			sendWebhookTraffic(result);
		}
		if (request.uploadFile() != null) {
			ResponseEntity<Map<String, Object>> invalid = uploadFile(request.uploadFile(), result);
			if (invalid != null) {
				return invalid;
			}
		}
		return ResponseEntity.ok(result);
	}

	@GetMapping("/controls")
	public Map<String, Object> controlsState() {
		return Map.of("pollingApiEnabled", pollingApiEnabled);
	}

	@GetMapping(value = "/orders", produces = MediaType.APPLICATION_JSON_VALUE)
	public synchronized String orders(@RequestParam("time_since") long timeSince) {
		if (!pollingApiEnabled) {
			return EMPTY_RESPONSE;
		}
		if (lines == null) {
			lines = loadLines(pollingDataFile);
		}
		if (lastTimeSince == null || timeSince > lastTimeSince) {
			index = lastTimeSince == null ? 0 : index + 1;
			lastTimeSince = timeSince;
		} else if (index < lines.size() && isErrorLine(lines.get(index))) {
			// re-poll of an unadvanced window whose line was a scripted
			// error: the "transient" failure clears on retry
			index++;
		}
		if (index >= lines.size()) {
			if (!exhaustionLogged) {
				exhaustionLogged = true;
				log.info("Mock polling data exhausted after {} lines — serving empty deltas from now on",
						lines.size());
			}
			return EMPTY_RESPONSE; // no more changes
		}
		return lines.get(index);
	}

	/**
	 * Fires every event in the webhook sample file at the real webhook intake
	 * endpoint, as fast as the send pool allows. Blocks until every event has
	 * been attempted; failed sends are counted and logged, not retried.
	 */
	private void sendWebhookTraffic(Map<String, Object> result) {
		List<String> events = loadLines(webhookDataFile);
		log.info("Replaying {} webhook events to {} on {} threads",
				events.size(), webhookEndpoint, WEBHOOK_SEND_THREADS);
		AtomicInteger failures = new AtomicInteger();
		try (ExecutorService pool = Executors.newFixedThreadPool(WEBHOOK_SEND_THREADS)) {
			for (String event : events) {
				pool.submit(() -> {
					try {
						restClient.post()
								.uri(webhookEndpoint)
								.contentType(MediaType.APPLICATION_JSON)
								.body(event)
								.retrieve()
								.toBodilessEntity();
					} catch (Exception e) {
						failures.incrementAndGet();
						log.warn("Webhook send to {} failed: {}", webhookEndpoint, e.toString());
					}
				});
			}
		} // close() waits for all submitted sends to finish
		int failed = failures.get();
		log.info("Webhook traffic replay done: {} sent, {} failed", events.size() - failed, failed);
		result.put("webhookSent", events.size() - failed);
		result.put("webhookFailed", failed);
	}

	/**
	 * POSTs the named order-sources CSV to the real ingest endpoint, as if a
	 * user uploaded it. Returns a 400 response for a bad file name (and null
	 * when handled — success or a reported upload error — so controls() can
	 * continue).
	 */
	private ResponseEntity<Map<String, Object>> uploadFile(String fileName, Map<String, Object> result) {
		if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
			return ResponseEntity.badRequest()
					.body(Map.of("error", "uploadFile must be a plain file name"));
		}
		Resource csv = resourceLoader.getResource(orderSourcesDir + fileName);
		if (!csv.exists()) {
			return ResponseEntity.badRequest()
					.body(Map.of("error", "no such order-sources file: " + fileName));
		}
		try {
			MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
			parts.add("file", csv);
			Map<?, ?> ingestResponse = restClient.post()
					.uri(ingestEndpoint)
					.contentType(MediaType.MULTIPART_FORM_DATA)
					.body(parts)
					.retrieve()
					.body(Map.class);
			log.info("Uploaded mock CSV {} to {}: {}", fileName, ingestEndpoint, ingestResponse);
			result.put("upload", ingestResponse);
		} catch (Exception e) {
			log.warn("Upload of {} to {} failed: {}", fileName, ingestEndpoint, e.toString());
			result.put("uploadError", e.toString());
		}
		return null;
	}

	private boolean isErrorLine(String line) {
		return !RESPONSE_200.matcher(line).find();
	}

	private List<String> loadLines(String location) {
		Resource resource = resourceLoader.getResource(location);
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
			return reader.lines()
					.filter(line -> !line.isBlank())
					.toList();
		} catch (Exception e) {
			log.warn("Mock data resource '{}' not readable — treating it as empty", location, e);
			return List.of();
		}
	}
}
