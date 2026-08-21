package com.lab37.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;

import com.lab37.model.ApiOrderQueue;
import com.lab37.model.ApiPolling;
import com.lab37.model.QueueStatus;
import com.lab37.repository.ApiOrderQueueRepository;
import com.lab37.repository.ApiPollingRepository;
import com.lab37.service.ApiPollResponse.ApiPollItem;

import tools.jackson.databind.json.JsonMapper;

class PollingApiPollerTest {

	private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

	// shaped like api_responses.jsonl line 2: one response, several orders
	private static final String MULTI_ORDER_BODY = """
			{"response": 200, "data": {
			  "7be39c2e042b576a218ea85f126f9ff98f4cea4a5655f03ca402430a0bc4f68f": \
			{"order": 4645, "name": "Grilled cheese with tomato soup", "category": "Lunch & Dinner", \
			"price": 12.74, "status": "ordered"},
			  "3c3458906cb4060d20a64ae1bf8045a86a5a583aeb3365d389a86aa55aa8b219": \
			{"order": 4646, "name": "Mashed potatoes and gravy", "category": "Side", \
			"price": 34.73, "status": "ordered"},
			  "3bc7e23af63736af1c34a0a54bee141f9e5262214eeeed89b045fe615c086c3b": \
			{"order": 4646, "name": "Ham and cheese croissant", "category": "Toasts & Light Bites", \
			"price": 47.05, "status": "ordered"}}}""";

	ApiOrderProcessor apiOrderProcessor;
	ApiOrderQueueRepository queueRepository;
	ApiPollingRepository pollingRepository;
	PollingApiPoller poller;

	@BeforeEach
	void setUp() {
		apiOrderProcessor = mock(ApiOrderProcessor.class);
		queueRepository = mock(ApiOrderQueueRepository.class);
		pollingRepository = mock(ApiPollingRepository.class);
		poller = new PollingApiPoller(RestClient.builder(), apiOrderProcessor, queueRepository,
				pollingRepository, new JsonMapper(),
				Clock.fixed(NOW, ZoneOffset.UTC),
				"http://localhost:8081/orders", 5, true);
	}

	@Test
	void usableResponseIsEnqueuedAsSingleRawBodyRow() {
		assertThat(poller.enqueueResponse(MULTI_ORDER_BODY)).isTrue();

		// ONE row for the whole response — a single atomic insert, never a
		// partially-enqueued response
		ArgumentCaptor<ApiOrderQueue> captor = ArgumentCaptor.forClass(ApiOrderQueue.class);
		verify(queueRepository).save(captor.capture());
		assertThat(captor.getValue().getPayload()).isEqualTo(MULTI_ORDER_BODY);
		assertThat(captor.getValue().getStatus()).isEqualTo(QueueStatus.RECEIVED);
		// nothing is applied on the polling path
		verify(apiOrderProcessor, never()).process(any(), any());
	}

	@Test
	void error500ResponseIsLoggedNotEnqueuedAndCursorHeld() {
		assertThat(poller.enqueueResponse(
				"{\"response\": 500, \"error\": \"internal server error\"}")).isFalse();
		verify(queueRepository, never()).save(any());
	}

	@Test
	void emptyDeltaAdvancesCursorWithoutEnqueueing() {
		assertThat(poller.enqueueResponse("{\"response\": 200, \"data\": {}}")).isTrue();
		verify(queueRepository, never()).save(any());
	}

	@Test
	void unparseableBodyIsLoggedAndCursorHeld() {
		assertThat(poller.enqueueResponse("<html>gateway timeout</html>")).isFalse();
		verify(queueRepository, never()).save(any());
	}

	@Test
	void cursorReadsEpochWhenTableEmptyAndPersistedValueOtherwise() {
		when(pollingRepository.findById(ApiPolling.SINGLETON_ID)).thenReturn(Optional.empty());
		assertThat(poller.currentTimeSince()).isZero();

		when(pollingRepository.findById(ApiPolling.SINGLETON_ID))
				.thenReturn(Optional.of(ApiPolling.of(1_766_000_000L)));
		assertThat(poller.currentTimeSince()).isEqualTo(1_766_000_000L);
	}

	@Test
	void advanceCursorUpsertsTheSingletonRow() {
		when(pollingRepository.findById(ApiPolling.SINGLETON_ID)).thenReturn(Optional.empty());

		poller.advanceCursor(NOW);

		ArgumentCaptor<ApiPolling> captor = ArgumentCaptor.forClass(ApiPolling.class);
		verify(pollingRepository).save(captor.capture());
		assertThat(captor.getValue().getLastPolled()).isEqualTo(NOW.toEpochMilli());
	}

	@Test
	void queueConsumerGroupsByOrderAndProcessesEachOrderOnce() {
		ApiOrderQueue entry = ApiOrderQueue.received(MULTI_ORDER_BODY, NOW);
		when(queueRepository.findProcessable(5)).thenReturn(List.of(entry));

		poller.processApiOrderQueue();

		verify(apiOrderProcessor).process(eq("4645"), any());
		ArgumentCaptor<Map<String, ApiPollItem>> captor = ArgumentCaptor.captor();
		verify(apiOrderProcessor).process(eq("4646"), captor.capture());
		assertThat(captor.getValue()).hasSize(2); // both 4646 items in one call
		assertThat(entry.getStatus()).isEqualTo(QueueStatus.PROCESSED);
	}

	@Test
	void partialFailureShrinksPayloadToFailedOrdersOnly() {
		ApiOrderQueue entry = ApiOrderQueue.received(MULTI_ORDER_BODY, NOW);
		when(queueRepository.findProcessable(5)).thenReturn(List.of(entry));
		// order 4645 fails, 4646 succeeds
		doThrow(new RuntimeException("db down")).when(apiOrderProcessor).process(eq("4645"), any());

		poller.processApiOrderQueue();

		verify(apiOrderProcessor).process(eq("4646"), any()); // other orders still applied
		assertThat(entry.getStatus()).isEqualTo(QueueStatus.ERROR);
		assertThat(entry.getRetryCount()).isEqualTo(1);
		assertThat(entry.getError()).contains("4645").contains("db down");

		// the payload was rewritten to only 4645's items — the retry will not
		// replay the already-applied 4646
		ApiPollResponse shrunk = new JsonMapper().readValue(entry.getPayload(), ApiPollResponse.class);
		assertThat(shrunk.data()).hasSize(1);
		assertThat(shrunk.data().values()).allSatisfy(item -> assertThat(item.order()).isEqualTo(4645));

		// second pass over the shrunken entry: only 4645 is attempted again
		org.mockito.Mockito.reset(apiOrderProcessor);
		when(queueRepository.findProcessable(5)).thenReturn(List.of(entry));
		poller.processApiOrderQueue();
		verify(apiOrderProcessor).process(eq("4645"), any());
		verify(apiOrderProcessor, never()).process(eq("4646"), any());
		assertThat(entry.getStatus()).isEqualTo(QueueStatus.PROCESSED);
	}

	@Test
	void queueConsumerTerminatesUnparseableEntries() {
		ApiOrderQueue garbage = ApiOrderQueue.received("not json", NOW);
		when(queueRepository.findProcessable(5)).thenReturn(List.of(garbage));

		poller.processApiOrderQueue();

		assertThat(garbage.getStatus()).isEqualTo(QueueStatus.PROCESSING_FAILURE);
		assertThat(garbage.getError()).contains("unparseable");
		verify(apiOrderProcessor, never()).process(any(), any());
	}
}
