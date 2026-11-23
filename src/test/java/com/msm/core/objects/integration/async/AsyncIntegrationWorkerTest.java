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

    private final IntegrationClient integrationClient = mock(IntegrationClient.class);
    private final ConnectorRequestBuilder client = mock(ConnectorRequestBuilder.class);
    private final IntegrationTaskStore store = mock(IntegrationTaskStore.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final RecordingHandler handler = new RecordingHandler();
    private final UUID taskId = UUID.randomUUID();
    private final UUID attemptId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(integrationClient.connector(any(ConnectorNamed.class))).thenReturn(client);
        when(client.attribute(anyString(), any())).thenReturn(client);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    private AsyncIntegrationWorker worker(Executor executor) {
        return new AsyncIntegrationWorker(executor, integrationClient, new IntegrationCallRunner(new IntegrationOutcomeClassifier()),
                store, transactionManager);
    }

    private AsyncTaskRun<Map<String, Object>> run() {
        IntegrationTaskContext task = IntegrationTaskContext.builder().taskId(taskId).attemptId(attemptId).attemptCount(1)
                .connector(RecordingHandler.CONNECTOR).action(RecordingHandler.CONNECTOR).objectName("v2order")
                .recordId(UUID.randomUUID()).payload(Map.of("code", "DH1")).context(Map.of()).async(true).build();
        return new AsyncTaskRun<>(handler, task, Map.of("code", "DH1"));
    }

    @Test
    void resultWrittenUnderItsAttemptRunsTheCallbackThenAfterCommit() {
        // Arrange
        when(store.finish(eq(taskId), eq(attemptId), any())).thenReturn(1);

        // Act
        worker(Runnable::run).submit(run());

        // Assert
        verify(client).attribute(HttpRequestContext.ATTRIBUTE_ASYNC_TASK_ID, taskId);
        ArgumentCaptor<ClassifiedOutcome> outcome = ArgumentCaptor.forClass(ClassifiedOutcome.class);
        verify(store).finish(eq(taskId), eq(attemptId), outcome.capture());
        assertThat(outcome.getValue().status()).isEqualTo(IntegrationTaskStatus.SUCCEEDED);
        assertThat(handler.events).containsExactly("call", "classify", "onSucceeded", "afterSucceededCommit");
        verify(transactionManager).commit(any());
    }

    @Test
    void staleAttemptCannotOverwriteTheTask() {
        // Arrange: the sweeper (or a new sending) changed the task first
        when(store.finish(eq(taskId), eq(attemptId), any())).thenReturn(0);

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
        when(store.finish(eq(taskId), eq(attemptId), any())).thenReturn(1);

        // Act
        worker(full).submit(run());

        // Assert
        ArgumentCaptor<ClassifiedOutcome> outcome = ArgumentCaptor.forClass(ClassifiedOutcome.class);
        verify(store).finish(eq(taskId), eq(attemptId), outcome.capture());
        assertThat(outcome.getValue().status()).isEqualTo(IntegrationTaskStatus.FAILED);
        assertThat(outcome.getValue().outcome()).isEqualTo(IntegrationOutcome.EXECUTOR_FULL);
        assertThat(handler.events).containsExactly("onFailed");
    }

    @Test
    void failingCallbackRollsBackAndNeverEscapes() {
        // Arrange
        when(store.finish(eq(taskId), eq(attemptId), any())).thenReturn(1);
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
        doThrow(new IllegalStateException("db down")).when(store).finish(any(), any(), any());

        // Act / Assert
        assertThatCode(() -> worker(Runnable::run).submit(run())).doesNotThrowAnyException();
        assertThat(handler.events).containsExactly("call", "classify");
    }
}
