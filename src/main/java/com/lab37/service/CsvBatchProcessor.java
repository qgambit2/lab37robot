package com.lab37.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.lab37.model.Order;
import com.lab37.model.OrderItem;
import com.lab37.model.OrderStatus;
import com.lab37.model.UploadFile;
import com.lab37.model.UploadJob;
import com.lab37.repository.OrderItemRepository;
import com.lab37.repository.OrderRepository;
import com.lab37.repository.UploadFileRepository;
import com.lab37.repository.UploadJobRepository;

/**
 * Converts a claimed upload job's CSV file into order rows, one batch per
 * transaction. Each batch commits its orders, their history snapshots, and
 * the job's advanced byte_offset atomically — so a crash mid-file loses at
 * most the current (uncommitted) batch and the next run resumes exactly at
 * the last committed offset, without duplicating orders.
 *
 * Parsing is record-based via commons-csv (quoted fields may contain commas
 * AND newlines, as the sample data does), with the parser's byte tracking
 * providing the resume offsets.
 */
@Component
public class CsvBatchProcessor {

	private static final Logger log = LoggerFactory.getLogger(CsvBatchProcessor.class);

	/** CSV column order: first_name,last_name,items,notes,tomorrow,meal */
	private static final int EXPECTED_COLUMNS = 6;

	private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT.builder().setTrim(true).get();

	/**
	 * Splits an items field into dish names. Sample data separates dishes
	 * with commas and/or newlines, but a comma inside parentheses is part of
	 * the dish name — "Milkshakes (vanilla, chocolate)" is one item.
	 */
	private static final Pattern ITEM_SEPARATOR = Pattern.compile("[,\\n](?![^(]*\\))");

	private final OrderRepository orderRepository;
	private final OrderItemRepository orderItemRepository;
	private final HistoryRecorder historyRecorder;
	private final UploadJobRepository uploadJobRepository;
	private final UploadFileRepository uploadFileRepository;
	private final DispatchWindowCalculator dispatchWindowCalculator;
	private final OrderLengthValidator orderLengthValidator;
	private final int batchSize;

	public CsvBatchProcessor(OrderRepository orderRepository,
			OrderItemRepository orderItemRepository,
			HistoryRecorder historyRecorder,
			UploadJobRepository uploadJobRepository,
			UploadFileRepository uploadFileRepository,
			DispatchWindowCalculator dispatchWindowCalculator,
			OrderLengthValidator orderLengthValidator,
			@Value("${ingest.batch-size}") int batchSize) {
		this.orderRepository = orderRepository;
		this.orderItemRepository = orderItemRepository;
		this.historyRecorder = historyRecorder;
		this.uploadJobRepository = uploadJobRepository;
		this.uploadFileRepository = uploadFileRepository;
		this.dispatchWindowCalculator = dispatchWindowCalculator;
		this.orderLengthValidator = orderLengthValidator;
		this.batchSize = batchSize;
	}

	/**
	 * Processes up to {@code ingest.batch-size} CSV records starting at the
	 * job's current byte_offset, then persists the advanced offset in the same
	 * transaction. Returns true if the file has more records to process.
	 */
	@Transactional
	public boolean processNextBatch(UploadJob job) throws IOException {
		byte[] content = uploadFileRepository.findById(job.getId())
				.map(UploadFile::getContent)
				.orElseThrow(() -> new IllegalStateException(
						"No stored file content for job " + job.getId()));
		long base = job.getByteOffset();
		try (InputStream in = new ByteArrayInputStream(content)) {
			in.skipNBytes(base);
			try (CSVParser parser = CSVParser.builder()
					.setInputStream(in)
					.setCharset(StandardCharsets.UTF_8)
					.setFormat(CSV_FORMAT)
					.setTrackBytes(true)
					.get()) {
				Iterator<CSVRecord> records = parser.iterator();
				if (base == 0 && records.hasNext()) {
					records.next(); // consume the header row
				}
				int processed = 0;
				while (processed < batchSize && records.hasNext()) {
					persistOrder(records.next(), job);
					processed++;
				}
				if (records.hasNext()) {
					// peek at the next record: its start is where the next
					// batch must resume (the peeked record is NOT persisted
					// here — it is re-read from the new offset next batch)
					CSVRecord next = records.next();
					job.setByteOffset(base + next.getBytePosition());
					uploadJobRepository.save(job);
					return true;
				}
				job.setByteOffset(content.length);
				uploadJobRepository.save(job);
				return false;
			}
		}
	}

	private void persistOrder(CSVRecord record, UploadJob job) {
		if (record.size() != EXPECTED_COLUMNS) {
			log.warn("Job {}: skipping malformed record at byte {} ({} columns, expected {})",
					job.getId(), record.getBytePosition(), record.size(), EXPECTED_COLUMNS);
			return;
		}
		String tomorrowRaw = record.get(4);
		String meal = record.get(5);
		// any schedule problem (blank or invalid meal/tomorrow) still saves
		// the order — as UNFULFILLED, with the reason in the error column
		String validationError = null;
		if (tomorrowRaw.isBlank() || meal.isBlank()) {
			validationError = "missing meal and/or tomorrow";
		} else if (!"true".equalsIgnoreCase(tomorrowRaw) && !"false".equalsIgnoreCase(tomorrowRaw)) {
			validationError = "tomorrow must be true/false, was '" + tomorrowRaw + "'";
		} else if (!dispatchWindowCalculator.isKnownMeal(meal)) {
			validationError = "unknown meal '" + meal + "'";
		}
		List<String> itemNames = Stream.of(ITEM_SEPARATOR.split(record.get(2)))
				.map(String::strip)
				.filter(name -> !name.isEmpty())
				.toList();
		Order order = Order.fromCsv(job.getId(), record.get(0), record.get(1), record.get(2),
				record.get(3), Boolean.parseBoolean(tomorrowRaw), meal);
		if (orderLengthValidator.exceedsLimits(itemNames)) {
			// oversized orders are saved for visibility but never dispatched —
			// the robot shouldn't receive them
			order.setOrderStatus(OrderStatus.ERROR);
			order.setError(OrderLengthValidator.ERROR_MESSAGE);
			log.warn("Job {}: order {} rejected — {}", job.getId(), order.getId(),
					OrderLengthValidator.ERROR_MESSAGE);
		} else if (validationError != null) {
			markUnfulfilled(order, job, validationError);
		} else {
			dispatchWindowCalculator.windowFor(meal, Boolean.parseBoolean(tomorrowRaw)).ifPresentOrElse(
					window -> order.setDispatchTimeInterval(window.start(), window.end()),
					// past today's cutoff: cannot be scheduled as-is (an order
					// change before dispatch could still make it schedulable)
					() -> markUnfulfilled(order, job,
							"same-day " + meal + " order received past its window cutoff"));
		}
		orderRepository.save(order);
		// normalize the items field into order_items rows;
		// CSV items carry no price (pricing is determined elsewhere)
		for (String name : itemNames) {
			orderItemRepository.save(OrderItem.of(order.getId(), name, null));
		}
		historyRecorder.snapshot(order);
	}

	private void markUnfulfilled(Order order, UploadJob job, String reason) {
		order.setOrderStatus(OrderStatus.UNFULFILLED);
		order.setError(reason);
		log.warn("Job {}: order {} UNFULFILLED — {}", job.getId(), order.getId(), reason);
	}
}
