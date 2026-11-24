package com.msm.core.objects.integration.async;

import com.msm.core.objects.integration.ConnectorNamed;
import com.msm.core.objects.integration.ConnectorRequestBuilder;
import com.msm.core.objects.integration.IntegrationClient;
import com.msm.core.objects.integration.context.HttpRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncIntegrationWorkerTest {

    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final long CALL_TIMEOUT_MS = 310_000L;
    private static final long DEADLINE_MS = 360_000L;

    private final IntegrationClient integrationClient = mock(IntegrationClient.class);
    private final ConnectorRequestBuilder client = mock(ConnectorRequestBuilder.class);
    private final IntegrationTaskStore store = mock(IntegrationTaskStore.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final RecordingHandler handler = new RecordingHandler();
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(integrationClient.connector(any(ConnectorNamed.class))).thenReturn(client);
        when(client.attribute(anyString(), any())).thenReturn(client);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    private AsyncIntegrationWorker worker(Executor executor) {
        return new AsyncIntegrationWorker(executor, integrationClient, new IntegrationCallRunner(new IntegrationOutcomeClassifier()),
                store, transactionManager, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** A task started just now: its call fits well before the deadline. */
    private AsyncTaskRun<Map<String, Object>> run() {
        return run(NOW.plusMillis(DEADLINE_MS));
    }

    private AsyncTaskRun<Map<String, Object>> run(Instant deadlineAt) {
        IntegrationTaskContext task = IntegrationTaskContext.builder().taskId(taskId)
                .connector(RecordingHandler.CONNECTOR).action(RecordingHandler.CONNECTOR).objectName("v2order")
                .recordId(UUID.randomUUID()).payload(Map.of("code", "DH1")).context(Map.of()).async(true).build();
        return new AsyncTaskRun<>(handler, task, Map.of("code", "DH1"), deadlineAt, CALL_TIMEOUT_MS);
    }

    @Test
    void resultWrittenWhileProcessingRunsTheCallbackThenAfterCommit() {
        // Arrange
        when(store.finish(eq(taskId), any())).thenReturn(1);

        // Act
        worker(Runnable::run).submit(run());

        // Assert
        verify(client).attribute(HttpRequestContext.ATTRIBUTE_ASYNC_TASK_ID, taskId);
        ArgumentCaptor<ClassifiedOutcome> outcome = ArgumentCaptor.forClass(ClassifiedOutcome.class);
        verify(store).finish(eq(taskId), outcome.capture());
        assertThat(outcome.getValue().status()).isEqualTo(IntegrationTaskStatus.SUCCEEDED);
        assertThat(handler.events).containsExactly("call", "classify", "onSucceeded", "afterSucceededCommit");
        verify(transactionManager).commit(any());
    }

    @Test
    void resultAfterTheSweeperClosedTheTaskIsDropped() {
        // Arrange: the sweeper closed the task first
        when(store.finish(eq(taskId), any())).thenReturn(0);

        // Act
        worker(Runnable::run).submit(run());

        // Assert: no callback, no after-commit work
        assertThat(handler.events).containsExactly("call", "classify");
    }

    @Test
    void fullExecutorFailsTheTaskWithoutSending() {
        // Arrange
        Executor full = command -> {
            throw new RejectedExecutionException("full");
        };
        when(store.finish(eq(taskId), any())).thenReturn(1);

        // Act
        worker(full).submit(run());

        // Assert
        ArgumentCaptor<ClassifiedOutcome> outcome = ArgumentCaptor.forClass(ClassifiedOutcome.class);
        verify(store).finish(eq(taskId), outcome.capture());
        assertThat(outcome.getValue().status()).isEqualTo(IntegrationTaskStatus.FAILED);
        assertThat(outcome.getValue().outcome()).isEqualTo(IntegrationOutcome.EXECUTOR_FULL);
        assertThat(handler.events).containsExactly("onFailed");
    }

    @Test
    void failingCallbackRollsBackAndNeverEscapes() {
        // Arrange
        when(store.finish(eq(taskId), any())).thenReturn(1);
        handler.onSucceededError = new IllegalStateException("cannot update the order");

        // Act / Assert
        assertThatCode(() -> worker(Runnable::run).submit(run())).doesNotThrowAnyException();
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        assertThat(handler.events).doesNotContain("afterSucceededCommit");
    }

    @Test
    void failingStoreNeverEscapesTheThread() {
        // Arrange
        doThrow(new IllegalStateException("db down")).when(store).finish(any(), any());

        // Act / Assert
        assertThatCode(() -> worker(Runnable::run).submit(run())).doesNotThrowAnyException();
        assertThat(handler.events).containsExactly("call", "classify");
    }

    @Test
    void callThatCouldEndPastTheDeadlineIsNotSent() {
        // Arrange: the task waited in the queue; 310 s of call no longer fit in the 309.999 s left
        when(store.finish(eq(taskId), any())).thenReturn(1);
        AsyncTaskRun<Map<String, Object>> late = run(NOW.plusMillis(CALL_TIMEOUT_MS - 1));

        // Act
        worker(Runnable::run).submit(late);

        // Assert: the partner is not called, the task is FAILED through the fenced finish
        verify(integrationClient, never()).connector(any(ConnectorNamed.class));
        ArgumentCaptor<ClassifiedOutcome> outcome = ArgumentCaptor.forClass(ClassifiedOutcome.class);
        verify(store).finish(eq(taskId), outcome.capture());
        assertThat(outcome.getValue().status()).isEqualTo(IntegrationTaskStatus.FAILED);
        assertThat(outcome.getValue().outcome()).isEqualTo(IntegrationOutcome.EXPIRED_BEFORE_SEND);
        assertThat(outcome.getValue().error().code()).isEqualTo(IntegrationOutcome.EXPIRED_BEFORE_SEND.name());
        assertThat(outcome.getValue().error().message()).startsWith("Not sent");
        assertThat(handler.events).containsExactly("onFailed");
    }

    @Test
    void callThatEndsExactlyAtTheDeadlineIsSent() {
        // Arrange: now + 310 s = deadline_at
        when(store.finish(eq(taskId), any())).thenReturn(1);
        AsyncTaskRun<Map<String, Object>> justInTime = run(NOW.plusMillis(CALL_TIMEOUT_MS));

        // Act
        worker(Runnable::run).submit(justInTime);

        // Assert
        verify(client).attribute(HttpRequestContext.ATTRIBUTE_ASYNC_TASK_ID, taskId);
        assertThat(handler.events).containsExactly("call", "classify", "onSucceeded", "afterSucceededCommit");
    }

    @Test
    void expiredTaskAlreadyClosedBySweeperIsDropped() {
        // Arrange: the sweeper closed the task before the worker took it
        when(store.finish(eq(taskId), any())).thenReturn(0);

        // Act
        worker(Runnable::run).submit(run(NOW.minusMillis(1)));

        // Assert: nothing sent, no callback
        verify(integrationClient, never()).connector(any(ConnectorNamed.class));
        assertThat(handler.events).isEmpty();
    }

    @Test
    void sendNowGivesExpiredBeforeSendWithoutCallingThePartner() {
        // Arrange
        AsyncTaskRun<Map<String, Object>> late = run(NOW.plusMillis(CALL_TIMEOUT_MS - 1));

        // Act
        ClassifiedOutcome outcome = worker(Runnable::run).sendNow(late).join();

        // Assert: the inline path gets the FAILED result to handle; nothing recorded by the worker
        assertThat(outcome.status()).isEqualTo(IntegrationTaskStatus.FAILED);
        assertThat(outcome.outcome()).isEqualTo(IntegrationOutcome.EXPIRED_BEFORE_SEND);
        verify(integrationClient, never()).connector(any(ConnectorNamed.class));
        verify(store, never()).finish(any(), any());
        assertThat(handler.events).isEmpty();
    }
}
