package com.msm.core.objects.integration.async;

import com.msm.core.action.context.ActionContext;
import com.msm.core.objects.config.IntegrationProperties;
import com.msm.core.objects.exception.IntegrationTaskInProgressException;
import com.msm.core.objects.exception.IntegrationTaskProcessingException;
import com.msm.core.objects.exception.IntegrationTaskRequestBudgetException;
import com.msm.core.objects.exception.ObjectErrors;
import com.msm.core.objects.exception.integration.IntegrationRequestException;
import com.msm.core.objects.integration.ConnectorNamed;
import com.msm.core.objects.integration.ConnectorRequestBuilder;
import com.msm.core.objects.integration.IntegrationClient;
import com.msm.core.objects.integration.data.AsyncProperties;
import com.msm.core.objects.integration.data.ConnectorProperties;
import com.msm.core.objects.integration.data.TimeoutProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code async.inline-wait-ms > 0} through the dispatcher, with the real worker and a mocked task table. */
class InlineWaitDispatchTest {

    private static final long DEADLINE_MS = 360_000L;
    private static final long READ_MS = 300_000L;
    private static final long LONG_WAIT_MS = 10_000L;
    private static final long SHORT_WAIT_MS = 100L;
    private static final long AWAIT_SECONDS = 10L;
    private static final String OBJECT = "v2order";
    private static final Executor IN_CALLER = Runnable::run;

    private final IntegrationClient integrationClient = mock(IntegrationClient.class);
    private final ConnectorRequestBuilder client = mock(ConnectorRequestBuilder.class);
    private final IntegrationTaskStore store = mock(IntegrationTaskStore.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final RecordingHandler handler = new RecordingHandler();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger commits = new AtomicInteger();
    private final UUID orderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(integrationClient.connector(any(ConnectorNamed.class))).thenReturn(client);
        when(client.attribute(any(), any())).thenReturn(client);
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        doAnswer(invocation -> commits.incrementAndGet()).when(transactionManager).commit(any());
        when(store.finish(any(), any())).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        TransactionSimulation.end();
        executor.shutdownNow();
        Thread.interrupted();
    }

    private AsyncIntegrationDispatcher dispatcher(Executor taskExecutor, long inlineWaitMs) {
        return dispatcher(taskExecutor, inlineWaitMs, Clock.systemUTC());
    }

    /** {@code workerClock}: the time the worker reads when it takes the task. */
    private AsyncIntegrationDispatcher dispatcher(Executor taskExecutor, long inlineWaitMs, Clock workerClock) {
        AsyncProperties async = new AsyncProperties();
        async.setEnabled(true);
        async.setDeadlineMs(DEADLINE_MS);
        async.setInlineWaitMs(inlineWaitMs);
        ConnectorProperties connector = new ConnectorProperties();
        connector.setAsync(async);
        TimeoutProperties timeout = new TimeoutProperties();
        timeout.setReadMs(READ_MS);
        connector.setTimeout(timeout);
        IntegrationProperties properties = new IntegrationProperties();
        properties.getConnectors().put(RecordingHandler.CONNECTOR, connector);
        IntegrationCallRunner runner = new IntegrationCallRunner(new IntegrationOutcomeClassifier());
        AsyncIntegrationWorker worker = new AsyncIntegrationWorker(taskExecutor, integrationClient, runner, store, transactionManager,
                workerClock);
        return new AsyncIntegrationDispatcher(properties, integrationClient, runner, store, worker, transactionManager);
    }

    /** Inline wait {@code inlineWaitMs} within a request budget, the request having spent {@code elapsedMs}. */
    private AsyncIntegrationDispatcher dispatcherWithBudget(long inlineWaitMs, long budgetMs, long elapsedMs) {
        AsyncProperties async = new AsyncProperties();
        async.setEnabled(true);
        async.setDeadlineMs(DEADLINE_MS);
        async.setInlineWaitMs(inlineWaitMs);
        async.setRequestBudgetMs(budgetMs);
        ConnectorProperties connector = new ConnectorProperties();
        connector.setAsync(async);
        TimeoutProperties timeout = new TimeoutProperties();
        timeout.setReadMs(READ_MS);
        connector.setTimeout(timeout);
        IntegrationProperties properties = new IntegrationProperties();
        properties.getConnectors().put(RecordingHandler.CONNECTOR, connector);
        IntegrationCallRunner runner = new IntegrationCallRunner(new IntegrationOutcomeClassifier());
        AsyncIntegrationWorker worker = new AsyncIntegrationWorker(executor, integrationClient, runner, store, transactionManager,
                Clock.systemUTC());
        long startNanos = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(elapsedMs);
        return new AsyncIntegrationDispatcher(properties, integrationClient, runner, store, worker, transactionManager,
                new RequestBudget(System::nanoTime, () -> startNanos));
    }

    private ActionContext<Map<String, Object>> submit() {
        return ActionContext.<Map<String, Object>>builder().resource(OBJECT).action("submit").objectId(orderId).payload(Map.of()).build();
    }

    private NewIntegrationTask insertedTask() {
        ArgumentCaptor<NewIntegrationTask> inserted = ArgumentCaptor.forClass(NewIntegrationTask.class);
        verify(store).insert(inserted.capture());
        return inserted.getValue();
    }

    private List<ClassifiedOutcome> finishedOutcomes(int times) {
        ArgumentCaptor<ClassifiedOutcome> outcomes = ArgumentCaptor.forClass(ClassifiedOutcome.class);
        verify(store, times(times)).finish(any(), outcomes.capture());
        return outcomes.getAllValues();
    }

    private void blockPartnerUntil(CountDownLatch release) {
        handler.partner = payload -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of("HeaderId", 1);
        };
    }

    private void awaitWorker() throws InterruptedException {
        executor.shutdown();
        assertThat(executor.awaitTermination(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    private long count(String event) {
        return handler.events.stream().filter(event::equals).count();
    }

    @Test
    void resultWithinTheWaitIsHandledInTheCallersThreadAndTransaction() throws Exception {
        // Arrange
        AsyncIntegrationDispatcher dispatcher = dispatcher(executor, LONG_WAIT_MS);
        TransactionSimulation.begin();

        // Act
        IntegrationTaskView view = dispatcher.dispatch(submit(), handler, Map.of("code", "DH1"));

        // Assert: callback in this thread, task updated in this transaction, nothing after commit yet
        assertThat(view).isNull();
        assertThat(handler.events).containsExactly("prepare", "call", "classify", "onSucceeded");
        assertThat(handler.callbackThreads).containsExactly(Thread.currentThread());
        NewIntegrationTask task = insertedTask();
        IntegrationTaskContext delivered = handler.tasks.get(0);
        assertThat(delivered.getTaskId()).isEqualTo(task.id());
        assertThat(delivered.isAsync()).isFalse();
        verify(store).finish(eq(task.id()), any());
        assertThat(finishedOutcomes(1).get(0).status()).isEqualTo(IntegrationTaskStatus.SUCCEEDED);
        assertThat(commits.get()).isEqualTo(1);

        // Act
        TransactionSimulation.commit();
        awaitWorker();

        // Assert: after commit only, no closing write and no delivery by the worker
        assertThat(handler.events).containsExactly("prepare", "call", "classify", "onSucceeded", "afterSucceededCommit");
        assertThat(handler.callbackThreads).containsOnly(Thread.currentThread());
        verify(store, times(1)).finish(any(), any());
        assertThat(commits.get()).isEqualTo(1);
    }

    @Test
    void timeoutFailsTheActionAndTheWorkerDeliversOnceInANewTransaction() throws Exception {
        // Arrange
        CountDownLatch release = new CountDownLatch(1);
        blockPartnerUntil(release);
        AsyncIntegrationDispatcher dispatcher = dispatcher(executor, SHORT_WAIT_MS);
        TransactionSimulation.begin();

        // Act / Assert
        assertThatThrownBy(() -> dispatcher.dispatch(submit(), handler, Map.of("code", "DH1")))
                .isInstanceOf(IntegrationTaskProcessingException.class)
                .hasFieldOrPropertyWithValue("code.code", "integration.task.processing");
        NewIntegrationTask task = insertedTask();
        verify(store, never()).finish(any(), any());
        assertThat(handler.events).containsExactly("prepare", "call");

        // Act: the action rolls back, then the partner answers
        TransactionSimulation.rollback();
        release.countDown();
        awaitWorker();

        // Assert
        verify(store).finish(eq(task.id()), any());
        assertThat(finishedOutcomes(1).get(0).status()).isEqualTo(IntegrationTaskStatus.SUCCEEDED);
        assertThat(handler.events).containsExactly("prepare", "call", "classify", "onSucceeded", "afterSucceededCommit");
        assertThat(handler.callbackThreads).doesNotContain(Thread.currentThread());
        assertThat(handler.tasks.get(0).isAsync()).isTrue();
        assertThat(commits.get()).isEqualTo(2);
    }

    @Test
    void processingErrorCarriesTheTask() {
        // Arrange
        CountDownLatch release = new CountDownLatch(1);
        blockPartnerUntil(release);
        AsyncIntegrationDispatcher dispatcher = dispatcher(executor, SHORT_WAIT_MS);

        // Act
        IntegrationTaskProcessingException error = catchProcessing(dispatcher);
        release.countDown();

        // Assert
        NewIntegrationTask task = insertedTask();
        assertThat(error.getParams()).containsEntry(IntegrationTaskProcessingException.TASK_ID_PARAM, task.id())
                .containsEntry(IntegrationTaskProcessingException.RECORD_ID_PARAM, orderId)
                .containsEntry(IntegrationTaskProcessingException.OBJECT_PARAM, OBJECT);
    }

    @Test
    void aRequestWithoutTimeLeftFailsBeforeAnyTaskOrCall() {
        // Arrange: 27 s budget, 27.5 s already spent before the call
        AsyncIntegrationDispatcher dispatcher = dispatcherWithBudget(LONG_WAIT_MS, 27_000L, 27_500L);

        // Act / Assert
        assertThatThrownBy(() -> dispatcher.dispatch(submit(), handler, Map.of("code", "DH1")))
                .isInstanceOfSatisfying(IntegrationTaskRequestBudgetException.class, error -> {
                    assertThat(error.getCode().getCode()).isEqualTo("integration.task.request_budget_exceeded");
                    assertThat(error.getParams()).containsEntry(IntegrationTaskRequestBudgetException.RECORD_ID_PARAM, orderId)
                            .containsEntry(IntegrationTaskRequestBudgetException.BUDGET_MS_PARAM, 27_000L);
                });
        verify(store, never()).insert(any());
        assertThat(count("call")).isZero();
        assertThat(handler.tasks).isEmpty();
    }

    @Test
    void aSlowRequestWaitsOnlyWhatIsLeftOfItsBudget() {
        // Arrange: 10 s inline wait, but only 0.2 s left of the request's budget; the partner never answers in time
        CountDownLatch release = new CountDownLatch(1);
        blockPartnerUntil(release);
        AsyncIntegrationDispatcher dispatcher = dispatcherWithBudget(LONG_WAIT_MS, 27_000L, 26_800L);
        long before = System.nanoTime();

        // Act
        IntegrationTaskProcessingException error = catchProcessing(dispatcher);
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
        release.countDown();

        // Assert: the task was started and the action stopped waiting well before the 10 s inline wait
        assertThat(error.getParams()).containsEntry(IntegrationTaskProcessingException.TASK_ID_PARAM, insertedTask().id());
        assertThat(waitedMs).isLessThan(LONG_WAIT_MS / 2);
    }

    private IntegrationTaskProcessingException catchProcessing(AsyncIntegrationDispatcher dispatcher) {
        try {
            dispatcher.dispatch(submit(), handler, Map.of("code", "DH1"));
        } catch (IntegrationTaskProcessingException e) {
            return e;
        }
        throw new AssertionError("integration.task.processing expected");
    }

    @Test
    void resultAtTheEndOfTheWaitIsDeliveredExactlyOnce() throws Exception {
        // Arrange: answers around the wait, so some land in the request and some in the worker
        int calls = 200;
        long waitMs = 2;
        AtomicInteger call = new AtomicInteger();
        handler.partner = payload -> {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(call.getAndIncrement() % 5));
            return Map.of("HeaderId", 1);
        };
        AsyncIntegrationDispatcher dispatcher = dispatcher(executor, waitMs);
        int detached = 0;

        // Act
        for (int i = 0; i < calls; i++) {
            TransactionSimulation.begin();
            try {
                dispatcher.dispatch(submit(), handler, Map.of("code", "DH" + i));
                TransactionSimulation.commit();
            } catch (IntegrationTaskProcessingException e) {
                detached++;
                TransactionSimulation.rollback();
            }
        }
        awaitWorker();

        // Assert: every result recorded and delivered once, by one side
        verify(store, times(calls)).finish(any(), any());
        assertThat(count("onSucceeded")).isEqualTo(calls);
        assertThat(count("afterSucceededCommit")).isEqualTo(calls);
        assertThat(handler.tasks.stream().filter(IntegrationTaskContext::isAsync).count()).isEqualTo(detached);
    }

    @Test
    void callerRollbackAfterAFailureWritesTheTaskAgainInANewTransaction() {
        // Arrange
        AsyncIntegrationDispatcher dispatcher = dispatcher(IN_CALLER, LONG_WAIT_MS);
        handler.partner = payload -> {
            throw new IntegrationRequestException(400, "400", "bad");
        };
        IllegalStateException ownError = new IllegalStateException("v2order.submit.erp_error");
        handler.onFailedError = ownError;
        TransactionSimulation.begin();

        // Act / Assert: the handler fails the action as in sync mode
        assertThatThrownBy(() -> dispatcher.dispatch(submit(), handler, Map.of("code", "DH1"))).isSameAs(ownError);
        assertThat(handler.tasks.get(0).isAsync()).isFalse();
        verify(store, times(1)).finish(any(), any());

        // Act
        TransactionSimulation.rollback();

        // Assert
        NewIntegrationTask task = insertedTask();
        verify(store, times(2)).finish(eq(task.id()), any());
        ClassifiedOutcome closing = finishedOutcomes(2).get(1);
        assertThat(closing.status()).isEqualTo(IntegrationTaskStatus.FAILED);
        assertThat(closing.outcome()).isEqualTo(IntegrationOutcome.HTTP_ERROR);
        assertThat(commits.get()).isEqualTo(2);
    }

    @Test
    void callerRollbackAfterUnconfirmedLeavesTheTaskUnconfirmed() {
        // Arrange
        AsyncIntegrationDispatcher dispatcher = dispatcher(IN_CALLER, LONG_WAIT_MS);
        handler.partner = payload -> {
            throw new IntegrationRequestException(500, "500", "down");
        };
        IllegalStateException ownError = new IllegalStateException("v2order.submit.erp_error");
        handler.onUnconfirmedError = ownError;
        TransactionSimulation.begin();
        assertThatThrownBy(() -> dispatcher.dispatch(submit(), handler, Map.of("code", "DH1"))).isSameAs(ownError);

        // Act
        TransactionSimulation.rollback();

        // Assert
        ClassifiedOutcome closing = finishedOutcomes(2).get(1);
        assertThat(closing.status()).isEqualTo(IntegrationTaskStatus.UNCONFIRMED);
        assertThat(closing.outcome()).isEqualTo(IntegrationOutcome.NO_RESPONSE);
    }

    @Test
    void callerRollbackAfterSuccessFailsTheTaskAndKeepsTheAnswer() {
        // Arrange: the partner succeeded, a later step of the action fails
        AsyncIntegrationDispatcher dispatcher = dispatcher(IN_CALLER, LONG_WAIT_MS);
        TransactionSimulation.begin();
        dispatcher.dispatch(submit(), handler, Map.of("code", "DH1"));

        // Act
        TransactionSimulation.rollback();

        // Assert
        List<ClassifiedOutcome> outcomes = finishedOutcomes(2);
        assertThat(outcomes.get(0).status()).isEqualTo(IntegrationTaskStatus.SUCCEEDED);
        ClassifiedOutcome closing = outcomes.get(1);
        assertThat(closing.status()).isEqualTo(IntegrationTaskStatus.UNCONFIRMED);
        assertThat(closing.outcome()).isEqualTo(IntegrationOutcome.ACTION_ROLLED_BACK);
        assertThat(closing.response()).isEqualTo(Map.of("HeaderId", 1));
        assertThat(closing.error().message()).isEqualTo("Action rolled back after partner success");
        assertThat(handler.events).doesNotContain("afterSucceededCommit");
    }

    @Test
    void duplicateInFlightIsInProgressAndSendsNothing() {
        // Arrange
        AsyncIntegrationDispatcher dispatcher = dispatcher(executor, LONG_WAIT_MS);
        doThrow(ObjectErrors.integrationTaskInProgress(OBJECT, orderId, new RuntimeException("23505"))).when(store).insert(any());
        TransactionSimulation.begin();

        // Act / Assert
        assertThatThrownBy(() -> dispatcher.dispatch(submit(), handler, Map.of("code", "DH1")))
                .isInstanceOf(IntegrationTaskInProgressException.class)
                .hasFieldOrPropertyWithValue("code.code", "integration.task.in_progress");
        assertThat(handler.events).containsExactly("prepare");
        verify(transactionManager).rollback(any());
        verify(integrationClient, never()).connector(any(ConnectorNamed.class));
    }

    @Test
    void taskIsCommittedInItsOwnTransactionBeforeThePartnerIsCalled() throws Exception {
        // Arrange
        AtomicBoolean committedBeforeCall = new AtomicBoolean();
        handler.partner = payload -> {
            committedBeforeCall.set(commits.get() == 1);
            return Map.of("HeaderId", 1);
        };
        AsyncIntegrationDispatcher dispatcher = dispatcher(executor, LONG_WAIT_MS);
        TransactionSimulation.begin();

        // Act
        dispatcher.dispatch(submit(), handler, Map.of("code", "DH1"));
        TransactionSimulation.rollback();
        awaitWorker();

        // Assert: REQUIRES_NEW, committed before the call, untouched by the caller's rollback
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        InOrder order = inOrder(transactionManager, store);
        order.verify(transactionManager).getTransaction(definition.capture());
        order.verify(store).insert(any());
        order.verify(transactionManager).commit(any());
        assertThat(definition.getValue().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(committedBeforeCall).isTrue();
        verify(transactionManager, never()).rollback(any());
    }

    @Test
    void fullExecutorFailsTheTaskAndDeliversTheFailureInTheCaller() {
        // Arrange
        Executor full = command -> {
            throw new RejectedExecutionException("full");
        };
        AsyncIntegrationDispatcher dispatcher = dispatcher(full, LONG_WAIT_MS);
        IllegalStateException ownError = new IllegalStateException("v2order.submit.erp_error");
        handler.onFailedError = ownError;
        TransactionSimulation.begin();

        // Act / Assert
        assertThatThrownBy(() -> dispatcher.dispatch(submit(), handler, Map.of("code", "DH1"))).isSameAs(ownError);
        TransactionSimulation.rollback();

        // Assert: FAILED committed on its own, before the callback, written once
        ClassifiedOutcome outcome = finishedOutcomes(1).get(0);
        assertThat(outcome.status()).isEqualTo(IntegrationTaskStatus.FAILED);
        assertThat(outcome.outcome()).isEqualTo(IntegrationOutcome.EXECUTOR_FULL);
        assertThat(commits.get()).isEqualTo(2);
        assertThat(handler.events).containsExactly("prepare", "onFailed");
        assertThat(handler.tasks.get(0).isAsync()).isFalse();
    }

    @Test
    void interruptedWaitDetachesAndKeepsTheInterrupt() throws Exception {
        // Arrange
        CountDownLatch release = new CountDownLatch(1);
        blockPartnerUntil(release);
        AsyncIntegrationDispatcher dispatcher = dispatcher(executor, LONG_WAIT_MS);
        Thread.currentThread().interrupt();

        // Act / Assert
        assertThatThrownBy(() -> dispatcher.dispatch(submit(), handler, Map.of("code", "DH1")))
                .isInstanceOf(IntegrationTaskProcessingException.class)
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(Thread.interrupted()).isTrue();

        // Act
        release.countDown();
        awaitWorker();

        // Assert
        assertThat(count("onSucceeded")).isEqualTo(1);
        assertThat(handler.tasks.get(0).isAsync()).isTrue();
    }

    @Test
    void resultThatCannotBeClassifiedIsUnconfirmed() {
        // Arrange
        AsyncIntegrationDispatcher dispatcher = dispatcher(IN_CALLER, LONG_WAIT_MS);
        handler.classifyFailure = new IllegalStateException("unexpected answer");
        TransactionSimulation.begin();

        // Act
        dispatcher.dispatch(submit(), handler, Map.of("code", "DH1"));

        // Assert
        assertThat(handler.events).containsExactly("prepare", "call", "classify", "onUnconfirmed");
        assertThat(handler.lastError.code()).isEqualTo(IntegrationOutcome.UNKNOWN_ERROR.name());
        assertThat(handler.lastError.cause()).isSameAs(handler.classifyFailure);
        assertThat(finishedOutcomes(1).get(0).status()).isEqualTo(IntegrationTaskStatus.UNCONFIRMED);
    }

    @Test
    void finishedTaskDoesNotBlockTheNextDispatch() {
        // Arrange: FAILED, then UNCONFIRMED, then a new try
        AsyncIntegrationDispatcher dispatcher = dispatcher(IN_CALLER, LONG_WAIT_MS);
        AtomicInteger call = new AtomicInteger();
        handler.partner = payload -> switch (call.getAndIncrement()) {
            case 0 -> throw new IntegrationRequestException(400, "400", "bad");
            case 1 -> throw new IntegrationRequestException(500, "500", "down");
            default -> Map.of("HeaderId", 1);
        };

        // Act
        for (int i = 0; i < 3; i++) {
            TransactionSimulation.begin();
            dispatcher.dispatch(submit(), handler, Map.of("code", "DH1"));
            TransactionSimulation.commit();
        }

        // Assert: a new task each time, same record, run to the end
        ArgumentCaptor<NewIntegrationTask> inserted = ArgumentCaptor.forClass(NewIntegrationTask.class);
        verify(store, times(3)).insert(inserted.capture());
        assertThat(inserted.getAllValues()).extracting(NewIntegrationTask::recordId).containsOnly(orderId);
        assertThat(inserted.getAllValues()).extracting(NewIntegrationTask::id).doesNotHaveDuplicates();
        assertThat(finishedOutcomes(3)).extracting(ClassifiedOutcome::status).containsExactly(
                IntegrationTaskStatus.FAILED, IntegrationTaskStatus.UNCONFIRMED, IntegrationTaskStatus.SUCCEEDED);
        assertThat(handler.events).containsSubsequence("onFailed", "onUnconfirmed", "onSucceeded", "afterSucceededCommit");
    }

    @Test
    void taskTakenTooLateIsNotSentAndFailsTheActionInTheCaller() {
        // Arrange: the worker takes the task 60 s after it started; 300 s of call no longer fit in the 300 s left
        Clock late = Clock.offset(Clock.systemUTC(), Duration.ofMillis(DEADLINE_MS - READ_MS + 1));
        AsyncIntegrationDispatcher dispatcher = dispatcher(IN_CALLER, LONG_WAIT_MS, late);
        IllegalStateException ownError = new IllegalStateException("v2order.submit.erp_error");
        handler.onFailedError = ownError;
        TransactionSimulation.begin();

        // Act / Assert: handled as any FAILED result within the wait
        assertThatThrownBy(() -> dispatcher.dispatch(submit(), handler, Map.of("code", "DH1"))).isSameAs(ownError);

        // Assert: the partner is not called, the task is FAILED EXPIRED_BEFORE_SEND
        verify(integrationClient, never()).connector(any(ConnectorNamed.class));
        assertThat(handler.events).containsExactly("prepare", "onFailed");
        assertThat(handler.tasks.get(0).isAsync()).isFalse();
        assertThat(handler.lastError.code()).isEqualTo(IntegrationOutcome.EXPIRED_BEFORE_SEND.name());
        ClassifiedOutcome outcome = finishedOutcomes(1).get(0);
        assertThat(outcome.status()).isEqualTo(IntegrationTaskStatus.FAILED);
        assertThat(outcome.outcome()).isEqualTo(IntegrationOutcome.EXPIRED_BEFORE_SEND);
    }
}
