package com.lab37.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.lab37.model.Order;
import com.lab37.model.OrderHistory;
import com.lab37.model.OrderItem;
import com.lab37.model.OrderStatus;
import com.lab37.model.OrderType;
import com.lab37.model.UploadFile;
import com.lab37.model.UploadJob;
import com.lab37.repository.OrderHistoryRepository;
import com.lab37.repository.OrderItemRepository;
import com.lab37.repository.OrderRepository;
import com.lab37.repository.UploadFileRepository;
import com.lab37.repository.UploadJobRepository;

class CsvBatchProcessorTest {

	private static final String HEADER = "first_name,last_name,items,notes,tomorrow,meal\n";
	private static final ZoneId ZONE = ZoneOffset.UTC;

	OrderRepository orderRepository;
	OrderItemRepository orderItemRepository;
	OrderHistoryRepository orderHistoryRepository;
	UploadJobRepository uploadJobRepository;
	UploadFileRepository uploadFileRepository;
	CsvBatchProcessor processor;

	@BeforeEach
	void setUp() {
		orderRepository = mock(OrderRepository.class);
		orderItemRepository = mock(OrderItemRepository.class);
		orderHistoryRepository = mock(OrderHistoryRepository.class);
		uploadJobRepository = mock(UploadJobRepository.class);
		uploadFileRepository = mock(UploadFileRepository.class);
		processor = processorAtLocalTime(6, 0); // before all dispatch windows
	}

	private CsvBatchProcessor processorAtLocalTime(int hour, int minute) {
		Clock clock = Clock.fixed(
				ZonedDateTime.of(2026, 8, 19, hour, minute, 0, 0, ZONE).toInstant(), ZONE);
		DispatchWindowCalculator calculator = new DispatchWindowCalculator(
				DispatchWindowCalculatorTest.defaultWindows(), clock);
		return new CsvBatchProcessor(orderRepository, orderItemRepository,
				new HistoryRecorder(orderHistoryRepository,
						mock(com.lab37.repository.ItemHistoryRepository.class), orderItemRepository),
				uploadJobRepository, uploadFileRepository, calculator,
				new OrderLengthValidator(), 2);
	}

	private UploadJob jobFor(String csvContent) {
		UploadJob job = UploadJob.queued("orders.csv");
		when(uploadFileRepository.findById(job.getId()))
				.thenReturn(Optional.of(UploadFile.of(job.getId(), csvContent.getBytes())));
		return job;
	}

	@Test
	void processesFileInBatchesAdvancingByteOffsetEachTime() throws Exception {
		String csv = HEADER
				+ "Alice,Smith,burger,none,false,lunch\n"
				+ "Bob,Jones,salad,extra dressing,true,dinner\n"
				+ "Carol,White,pizza,none,false,dinner\n";
		UploadJob job = jobFor(csv);
		long fileSize = csv.getBytes().length;

		boolean more = processor.processNextBatch(job);

		assertThat(more).isTrue();
		// the committed offset is exactly the start of the first unprocessed record
		assertThat(job.getByteOffset()).isEqualTo(csv.indexOf("Carol"));
		verify(orderRepository, times(2)).save(any(Order.class));

		more = processor.processNextBatch(job);

		assertThat(more).isFalse();
		assertThat(job.getByteOffset()).isEqualTo(fileSize);
		verify(orderRepository, times(3)).save(any(Order.class));
		verify(orderHistoryRepository, times(3)).save(any(OrderHistory.class));
		verify(uploadJobRepository, times(2)).save(job);
	}

	@Test
	void handlesQuotedMultilineFieldsAcrossBatches() throws Exception {
		// shaped like the real sample data: quoted items fields spanning lines
		String csv = HEADER
				+ "Wade,Wilson,\"Fried banana, Sweet tea, Apple pie,\nEspresso, Croissant\",,true,breakfast\n"
				+ "Alex,Summers,\"Multigrain sandwich loaf\nMilkshakes (vanilla, chocolate)\nPhilly cheesesteak\",,true,dinner\n"
				+ "Charles,Xavier,\"Coleslaw, Croissant\",,true,breakfast\n";
		UploadJob job = jobFor(csv);

		boolean more = processor.processNextBatch(job);

		assertThat(more).isTrue();
		// resume offset lands on the start of Charles's record, not mid-quote
		assertThat(job.getByteOffset()).isEqualTo(csv.indexOf("Charles"));
		ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository, times(2)).save(captor.capture());
		assertThat(captor.getAllValues().get(0).getItems())
				.isEqualTo("Fried banana, Sweet tea, Apple pie,\nEspresso, Croissant");
		assertThat(captor.getAllValues().get(1).getItems())
				.startsWith("Multigrain sandwich loaf\n");

		// items split on commas AND newlines, but parenthesized commas are
		// part of the dish name
		ArgumentCaptor<OrderItem> itemCaptor = ArgumentCaptor.forClass(OrderItem.class);
		verify(orderItemRepository, times(8)).save(itemCaptor.capture());
		assertThat(itemCaptor.getAllValues()).extracting(OrderItem::getItemName).containsExactly(
				"Fried banana", "Sweet tea", "Apple pie", "Espresso", "Croissant",
				"Multigrain sandwich loaf", "Milkshakes (vanilla, chocolate)",
				"Philly cheesesteak");

		assertThat(processor.processNextBatch(job)).isFalse();
		verify(orderRepository, times(3)).save(any(Order.class));
	}

	@Test
	void oversizedItemNameMarksOrderErrorInsteadOfCreated() throws Exception {
		String hugeItem = "x".repeat(OrderItem.NAME_MAX_LENGTH + 1);
		UploadJob job = jobFor(HEADER + "Alice,Smith," + hugeItem + ",,true,lunch\n");

		processor.processNextBatch(job);

		ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository).save(captor.capture());
		assertThat(captor.getValue().getOrderStatus()).isEqualTo(OrderStatus.ERROR);
		assertThat(captor.getValue().getError()).isEqualTo("order length exceeds maximum limit");
	}

	@Test
	void mapsCsvColumnsIncludingQuotedCommasToOrderFields() throws Exception {
		UploadJob job = jobFor(HEADER + "Alice,Smith,\"burger, fries\",\"no onions, please\",true,lunch\n");

		processor.processNextBatch(job);

		ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository).save(captor.capture());
		Order order = captor.getValue();
		assertThat(order.getFirstName()).isEqualTo("Alice");
		assertThat(order.getLastName()).isEqualTo("Smith");
		assertThat(order.getItems()).isEqualTo("burger, fries");
		assertThat(order.getNotes()).isEqualTo("no onions, please");
		assertThat(order.isTomorrow()).isTrue();
		assertThat(order.getMeal()).isEqualTo("lunch");
		assertThat(order.getAmount()).isNull(); // CSV orders carry no amount
		assertThat(order.getOrderSource()).isNull(); // webhook-only fields
		assertThat(order.getRestaurant()).isNull();
		assertThat(order.getOrderType()).isEqualTo(OrderType.SVC_FILE);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CREATED);

		// the quoted items field splits into normalized order_items rows
		ArgumentCaptor<OrderItem> itemCaptor = ArgumentCaptor.forClass(OrderItem.class);
		verify(orderItemRepository, times(2)).save(itemCaptor.capture());
		assertThat(itemCaptor.getAllValues())
				.extracting(OrderItem::getItemName)
				.containsExactly("burger", "fries");
		assertThat(itemCaptor.getAllValues()).allSatisfy(item -> {
			assertThat(item.getOrderId()).isEqualTo(order.getId());
			assertThat(item.getItemPrice()).isNull(); // CSV items carry no price
		});
		assertThat(order.getDispatchTime()).isNull(); // set only when sent to the robot
		// tomorrow=true + lunch → next day’s 11:30-13:30 UTC window
		assertThat(order.getDispatchTimeIntervalStart())
				.isEqualTo(ZonedDateTime.of(2026, 8, 20, 11, 30, 0, 0, ZONE).toInstant());
		assertThat(order.getDispatchTimeIntervalEnd())
				.isEqualTo(ZonedDateTime.of(2026, 8, 20, 13, 30, 0, 0, ZONE).toInstant());
		assertThat(order.getVersion()).isEqualTo(1);
		assertThat(order.getJobId()).isEqualTo(job.getId());
		assertThat(order.getCreatedAt()).isNotNull();
		assertThat(order.getUpdatedAt()).isNotNull();

		ArgumentCaptor<OrderHistory> historyCaptor = ArgumentCaptor.forClass(OrderHistory.class);
		verify(orderHistoryRepository).save(historyCaptor.capture());
		OrderHistory history = historyCaptor.getValue();
		assertThat(history.getOrderId()).isEqualTo(order.getId());
		assertThat(history.getVersion()).isEqualTo(1);
		assertThat(history.getItems()).isEqualTo("burger, fries");
	}

	@Test
	void sameDayOrderPastItsWindowCutoffIsSavedUnfulfilled() throws Exception {
		// 15:00: breakfast (7-9) is past cutoff, dinner (17:30-19:30) is not
		processor = processorAtLocalTime(15, 0);
		UploadJob job = jobFor(HEADER
				+ "Alice,Smith,pancakes,none,false,breakfast\n"
				+ "Bob,Jones,steak,none,false,dinner\n");

		processor.processNextBatch(job);

		ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository, times(2)).save(captor.capture());
		Order lateBreakfast = captor.getAllValues().get(0);
		assertThat(lateBreakfast.getOrderStatus()).isEqualTo(OrderStatus.UNFULFILLED);
		assertThat(lateBreakfast.getError()).contains("past its window cutoff");
		assertThat(lateBreakfast.getDispatchTimeIntervalStart()).isNull();
		assertThat(lateBreakfast.getDispatchTimeIntervalEnd()).isNull();
		Order dinner = captor.getAllValues().get(1);
		assertThat(dinner.getOrderStatus()).isEqualTo(OrderStatus.CREATED);
		assertThat(dinner.getError()).isNull();
		assertThat(dinner.getDispatchTimeIntervalStart()).isNotNull();
	}

	@Test
	void rowsWithBlankMealOrTomorrowAreSavedUnfulfilled() throws Exception {
		UploadJob job = jobFor(HEADER
				+ "Dana,Doe,toast,no butter,,\n"
				+ "Erin,Ray,fruit,,true,\n");

		processor.processNextBatch(job);

		ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository, times(2)).save(captor.capture());
		assertThat(captor.getAllValues()).allSatisfy(order -> {
			assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.UNFULFILLED);
			assertThat(order.getError()).contains("missing meal and/or tomorrow");
			assertThat(order.getDispatchTimeIntervalStart()).isNull();
			assertThat(order.getDispatchTimeIntervalEnd()).isNull();
		});
		assertThat(captor.getAllValues().get(0).getFirstName()).isEqualTo("Dana");
	}

	@Test
	void invalidValuesAreSavedUnfulfilledWithErrorOnlyStructuralRowsSkipped() throws Exception {
		String csv = HEADER
				+ "only,three,columns\n"
				+ "Bob,Jones,salad,none,maybe,dinner\n"
				+ "Carol,White,eggs,none,false,brunch\n"
				+ "Alice,Smith,burger,none,false,lunch\n";
		UploadJob job = jobFor(csv);
		long fileSize = csv.getBytes().length;

		assertThat(processor.processNextBatch(job)).isTrue();
		assertThat(processor.processNextBatch(job)).isFalse();
		assertThat(job.getByteOffset()).isEqualTo(fileSize);

		// wrong-column-count row is skipped (fields can't be mapped);
		// invalid values are saved UNFULFILLED with the reason recorded
		ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository, times(3)).save(captor.capture());
		Order bob = captor.getAllValues().get(0);
		assertThat(bob.getOrderStatus()).isEqualTo(OrderStatus.UNFULFILLED);
		assertThat(bob.getError()).contains("true/false").contains("maybe");
		Order carol = captor.getAllValues().get(1);
		assertThat(carol.getOrderStatus()).isEqualTo(OrderStatus.UNFULFILLED);
		assertThat(carol.getError()).contains("unknown meal 'brunch'");
		Order alice = captor.getAllValues().get(2);
		assertThat(alice.getOrderStatus()).isEqualTo(OrderStatus.CREATED);
		assertThat(alice.getError()).isNull();
	}

	@Test
	void emptyFileCompletesImmediately() throws Exception {
		UploadJob job = jobFor("");

		assertThat(processor.processNextBatch(job)).isFalse();
		assertThat(job.getByteOffset()).isZero();
		verify(orderRepository, never()).save(any(Order.class));
	}

	@Test
	void resumesFromPersistedByteOffsetWithoutRereadingEarlierRows() throws Exception {
		String csv = HEADER
				+ "Alice,Smith,burger,none,false,lunch\n"
				+ "Bob,Jones,salad,none,true,dinner\n"
				+ "Carol,White,pizza,none,false,dinner\n";
		UploadJob job = jobFor(csv);
		// simulate a prior run that committed through the first two data rows
		job.setByteOffset(csv.indexOf("Carol"));

		boolean more = processor.processNextBatch(job);

		assertThat(more).isFalse();
		ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository).save(captor.capture());
		assertThat(captor.getValue().getFirstName()).isEqualTo("Carol");
	}
}
