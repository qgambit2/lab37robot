package com.lab37.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.web.client.RestClient;

import com.lab37.controller.MockController.MockControlRequest;

class MockControllerTest {

	private static final String LINE_1 = "{\"response\": 200, \"data\": {\"aa\": {\"order\": 1}}}";
	private static final String LINE_2 = "{\"response\": 200, \"data\": {\"bb\": {\"order\": 2}}}";
	private static final String LINE_3 = "{\"response\": 500, \"error\": \"boom\"}";
	private static final String EMPTY = "{\"response\": 200, \"data\": {}}";

	// nothing listens here — webhook/ingest sends against it fail fast
	private static final String DEAD_WEBHOOK = "http://localhost:59999/v1/webhook";
	private static final String DEAD_INGEST = "http://localhost:59999/v1/ingest";

	@TempDir
	Path tempDir;

	Path dataFile;

	Path webhookFile;

	@BeforeEach
	void writeSampleFiles() throws Exception {
		dataFile = tempDir.resolve("responses.jsonl");
		Files.writeString(dataFile, LINE_1 + "\n" + LINE_2 + "\n" + LINE_3 + "\n");
		webhookFile = tempDir.resolve("webhooks.jsonl");
		Files.writeString(webhookFile, "{\"order_id\": \"a\"}\n{\"order_id\": \"b\"}\n{\"order_id\": \"c\"}\n");
	}

	private MockController mock(boolean enabled, Path pollingFile) {
		return new MockController(RestClient.builder(), new DefaultResourceLoader(), enabled,
				pollingFile.toUri().toString(), webhookFile.toUri().toString(),
				DEAD_WEBHOOK, DEAD_INGEST, tempDir.toUri().toString());
	}

	private MockControlRequest pollingApi(boolean enabled) {
		return new MockControlRequest(enabled, null, null);
	}

	@Test
	void startsDisabledAndServesEmptyDeltasUntilEnabled() {
		MockController mock = mock(false, dataFile);

		assertThat(mock.controlsState()).containsEntry("pollingApiEnabled", false);
		assertThat(mock.orders(100)).isEqualTo(EMPTY);

		mock.controls(pollingApi(true));

		assertThat(mock.controlsState()).containsEntry("pollingApiEnabled", true);
		assertThat(mock.orders(100)).isEqualTo(LINE_1);
	}

	@Test
	void advancesOneLinePerIncreasedTimeSinceAndRepeatsOnSameTimeSince() {
		MockController mock = mock(true, dataFile);

		assertThat(mock.orders(100)).isEqualTo(LINE_1); // first poll
		assertThat(mock.orders(100)).isEqualTo(LINE_1); // same time_since → same row
		assertThat(mock.orders(100)).isEqualTo(LINE_1);
		assertThat(mock.orders(150)).isEqualTo(LINE_2); // increased → next row
		assertThat(mock.orders(150)).isEqualTo(LINE_2);
		assertThat(mock.orders(200)).isEqualTo(LINE_3);
		assertThat(mock.orders(250)).isEqualTo(EMPTY); // sample data exhausted
		assertThat(mock.orders(300)).isEqualTo(EMPTY);
	}

	@Test
	void rePollOfSameWindowAdvancesPastScriptedErrorLine() throws Exception {
		// error first, then a 200 line — the poller's cursor won't advance
		// after the 500, so the retry arrives with the SAME time_since
		Path file = tempDir.resolve("error_first.jsonl");
		Files.writeString(file, LINE_3 + "\n" + LINE_1 + "\n");
		MockController mock = mock(true, file);

		assertThat(mock.orders(100)).isEqualTo(LINE_3); // error served once
		assertThat(mock.orders(100)).isEqualTo(LINE_1); // retry → error cleared
		assertThat(mock.orders(100)).isEqualTo(LINE_1); // 200 lines still repeat
		assertThat(mock.orders(150)).isEqualTo(EMPTY);
	}

	@Test
	void rePollAfterTrailingErrorLineExhaustsSampleData() {
		MockController mock = mock(true, dataFile);

		assertThat(mock.orders(100)).isEqualTo(LINE_1);
		assertThat(mock.orders(150)).isEqualTo(LINE_2);
		assertThat(mock.orders(200)).isEqualTo(LINE_3); // trailing error
		assertThat(mock.orders(200)).isEqualTo(EMPTY); // retry moves past the end
		assertThat(mock.orders(250)).isEqualTo(EMPTY);
	}

	@Test
	void disablingMidStreamPausesAndReEnablingResumes() {
		MockController mock = mock(true, dataFile);
		assertThat(mock.orders(100)).isEqualTo(LINE_1);

		mock.controls(pollingApi(false));
		assertThat(mock.orders(200)).isEqualTo(EMPTY);

		mock.controls(pollingApi(true));
		assertThat(mock.orders(300)).isEqualTo(LINE_2); // resumes where it stopped
	}

	@Test
	void controlsWithNoOptionsIsBadRequest() {
		MockController mock = mock(false, dataFile);

		var response = mock.controls(new MockControlRequest(null, null, null));

		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(mock.controlsState()).containsEntry("pollingApiEnabled", false);
	}

	@Test
	void missingDataFileServesEmptyDeltas() {
		MockController mock = mock(true, tempDir.resolve("nope.jsonl"));

		assertThat(mock.orders(100)).isEqualTo(EMPTY);
	}

	@Test
	void sendWebhookTrafficCountsFailedSends() {
		// every send hits the dead endpoint: all attempted, all counted failed
		MockController mock = mock(false, dataFile);

		Map<String, Object> result =
				mock.controls(new MockControlRequest(null, true, null)).getBody();

		assertThat(result)
				.containsEntry("webhookSent", 0)
				.containsEntry("webhookFailed", 3)
				.containsEntry("pollingApiEnabled", false); // state untouched
	}

	@Test
	void sendWebhookTrafficWithMissingFileSendsNothing() throws Exception {
		Files.delete(webhookFile);
		MockController mock = mock(false, dataFile);

		Map<String, Object> result =
				mock.controls(new MockControlRequest(null, true, null)).getBody();

		assertThat(result)
				.containsEntry("webhookSent", 0)
				.containsEntry("webhookFailed", 0);
	}

	@Test
	void uploadFileReportsFailedIngestCall() throws Exception {
		Files.writeString(tempDir.resolve("orders_1.csv"), "first_name\nAlice\n");
		MockController mock = mock(false, dataFile);

		Map<String, Object> result =
				mock.controls(new MockControlRequest(null, null, "orders_1.csv")).getBody();

		// the dead ingest endpoint was attempted and its failure surfaced
		assertThat(result).containsKey("uploadError");
	}

	@Test
	void uploadFileRejectsUnknownAndUnsafeNames() {
		MockController mock = mock(false, dataFile);

		assertThat(mock.controls(new MockControlRequest(null, null, "nope.csv"))
				.getStatusCode().value()).isEqualTo(400);
		assertThat(mock.controls(new MockControlRequest(null, null, "../secrets.csv"))
				.getStatusCode().value()).isEqualTo(400);
	}

	@Test
	void controlsCombinesOptionsInOneRequest() throws Exception {
		MockController mock = mock(false, dataFile);

		Map<String, Object> result =
				mock.controls(new MockControlRequest(true, true, null)).getBody();

		assertThat(result).containsEntry("pollingApiEnabled", true);
		assertThat(result).containsKeys("webhookSent", "webhookFailed");
		assertThat(mock.orders(100)).isEqualTo(LINE_1); // polling got enabled
	}
}
