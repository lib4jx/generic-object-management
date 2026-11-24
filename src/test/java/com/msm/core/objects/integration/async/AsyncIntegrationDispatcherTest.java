package com.msm.core.objects.integration.async;

import com.msm.core.action.context.ActionContext;
import com.msm.core.objects.config.IntegrationProperties;
import com.msm.core.objects.exception.IntegrationTaskInProgressException;
import com.msm.core.objects.exception.ObjectErrors;
import com.msm.core.objects.exception.integration.IntegrationRequestException;
import com.msm.core.objects.integration.ConnectorNamed;
import com.msm.core.objects.integration.ConnectorRequestBuilder;
import com.msm.core.objects.integration.IntegrationClient;
import com.msm.core.objects.integration.data.AsyncProperties;
import com.msm.core.objects.integration.data.ConnectorProperties;
import com.msm.core.objects.integration.data.TimeoutProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AsyncIntegrationDispatcherTest {

    private static final long DEADLINE_MS = 360_000L;
    private static final long CONNECT_MS = 10_000L;
    private static final long READ_MS = 300_000L;
    private static final String OBJECT = "v2order";

    private final IntegrationClient integrationClient = mock(IntegrationClient.class);
    private final IntegrationTaskStore store = mock(IntegrationTaskStore.class);
    private final AsyncIntegrationWorker worker = mock(AsyncIntegrationWorker.class);
    private final RecordingHandler handler = new RecordingHandler();
    private final UUID orderId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        TransactionSimulation.end();
    }

    private AsyncIntegrationDispatcher dispatcher(boolean async) {
        ConnectorProperties connector = new ConnectorProperties();
        if (async) {
            AsyncProperties asyncProperties = new AsyncProperties();
            asyncProperties.setEnabled(true);
            asyncProperties.setDeadlineMs(DEADLINE_MS);
            connector.setAsync(asyncProperties);
            TimeoutProperties timeout = new TimeoutProperties();
            timeout.setConnectMs(CONNECT_MS);
            timeout.setReadMs(READ_MS);
            connector.setTimeout(timeout);
        }
        IntegrationProperties properties = new IntegrationProperties();
        properties.getConnectors().put(RecordingHandler.CONNECTOR, connector);
        when(integrationClient.connector(any(ConnectorNamed.class))).thenReturn(mock(ConnectorRequestBuilder.class));
        return new AsyncIntegrationDispatcher(properties, integrationClient,
                new IntegrationCallRunner(new IntegrationOutcomeClassifier()), store, worker,
                mock(PlatformTransactionManager.class));
    }

    private ActionContext<Map<String, Object>> submit() {
        return ActionContext.<Map<String, Object>>builder().resource(OBJECT).action("submit").objectId(orderId).payload(Map.of()).build();
    }

    @Test
    void syncRunsEveryStepInTheRequestWithoutTask() {
        // Arrange
        AsyncIntegrationDispatcher dispatcher = dispatcher(false);
        TransactionSimulation.begin();

        // Act
        IntegrationTaskView view = dispatcher.dispatch(submit(), handler, Map.of("code", "DH1"));

        // Assert
        assertThat(view).isNull();
        assertThat(handler.events).containsExactly("prepare", "call", "classify", "onSucceeded");
        IntegrationTaskContext task = handler.tasks.get(0);
        assertThat(task.getTaskId()).isNull();
        assertThat(task.isAsync()).isFalse();
        assertThat(task.getRecordId()).isEqualTo(orderId);
        assertThat(task.getContextValue("target", String.class)).isEqualTo("CANCELED");
        verifyNoInteractions(store, worker);
    }

    @Test
    void syncAfterSucceededCommitWaitsForTheCommit() {
        // Arrange
        AsyncIntegrationDispatcher dispatcher = dispatcher(false);
        TransactionSimulation.begin();
        dispatcher.dispatch(submit(), handler, Map.of("code", "DH1"));
        assertThat(handler.events).doesNotContain("afterSucceededCommit");

        // Act
        TransactionSimulation.commit();

        // Assert
        assertThat(handler.events).endsWith("onSucceeded", "afterSucceededCommit");
    }

    @Test
    void syncRollbackSkipsAfterSucceededCommit() {
        // Arrange
        AsyncIntegrationDispatcher dispatcher = dispatcher(false);
        TransactionSimulation.begin();
        dispatcher.dispatch(submit(), handler, Map.of("code", "DH1"));

        // Act
        TransactionSimulation.rollback();

        // Assert
        assertThat(handler.events).doesNotContain("afterSucceededCommit");
    }

    @Test
    void syncFailurePropagatesTheHandlersOwnError() {
        // Arrange
        AsyncIntegrationDispatcher dispatcher = dispatcher(false);
        IntegrationRequestException partnerError = new IntegrationRequestException(400, "400", "bad");
        handler.partner = payload -> {
            throw partnerError;
        };
        IllegalStateException ownError = new IllegalStateException("v2order.submit.erp_error");
        handler.onFailedError = ownError;

        // Act / Assert
        assertThatThrownBy(() -> dispatcher.dispatch(submit(), handler, Map.of("code", "DH1"))).isSameAs(ownError);
        assertThat(handler.events).containsExactly("prepare", "call", "classifyError", "onFailed");
        assertThat(handler.lastError.cause()).isSameAs(partnerError);
        assertThat(handler.lastError.statusCode()).isEqualTo(400);
    }

    @Test
    void syncAlreadyDoneWithDataSucceedsWithThatData() {
        // Arrange
        AsyncIntegrationDispatcher dispatcher = dispatcher(false);
        handler.partner = payload -> {
            throw new IntegrationRequestException(500, "500", "already exists");
        };
        handler.errorBusiness = Optional.of(BusinessOutcome.alreadyDone("SO exists"));
        handler.alreadyDoneData = Optional.of(Map.of("HeaderId", 7));

        // Act
        dispatcher.dispatch(submit(), handler, Map.of("code", "DH1"));

        // Assert
        assertThat(handler.events).containsExactly("prepare", "call", "classifyError", "onAlreadyDone", "onSucceeded", "afterSucceededCommit");
        assertThat(handler.lastResponse).isEqualTo(Map.of("HeaderId", 7));
    }

    @Test
    void syncAlreadyDoneWithoutDataIsUnconfirmed() {
        // Arrange
        AsyncIntegrationDispatcher dispatcher = dispatcher(false);
        handler.partner = payload -> {
            throw new IntegrationRequestException(500, "500", "already exists");
        };
        handler.errorBusiness = Optional.of(BusinessOutcome.alreadyDone("SO exists"));

        // Act
        dispatcher.dispatch(submit(), handler, Map.of("code", "DH1"));

        // Assert
        assertThat(handler.events).containsExactly("prepare", "call", "classifyError", "onAlreadyDone", "onUnconfirmed");
        assertThat(handler.lastError.code()).isEqualTo(IntegrationOutcome.ALREADY_DONE.name());
    }

    @Test
    void asyncInsertsTheTaskAndSubmitsOnlyAfterCommit() {
        // Arrange
        AsyncIntegrationDispatcher dispatcher = dispatcher(true);
        TransactionSimulation.begin();

        // Act
        IntegrationTaskView view = dispatcher.dispatch(submit(), handler, Map.of("code", "DH1"));

        // Assert: task row in the action's transaction, nothing sent yet
        ArgumentCaptor<NewIntegrationTask> inserted = ArgumentCaptor.forClass(NewIntegrationTask.class);
        verify(store).insert(inserted.capture());
        NewIntegrationTask task = inserted.getValue();
        assertThat(task.id()).isEqualTo(view.id());
        assertThat(task.objectName()).isEqualTo(OBJECT);
        assertThat(task.recordId()).isEqualTo(orderId);
        assertThat(task.connector()).isEqualTo(RecordingHandler.CONNECTOR);
        assertThat(task.action()).isEqualTo(RecordingHandler.CONNECTOR);
        assertThat(task.context()).isEqualTo("{\"target\":\"CANCELED\"}");
        assertThat(task.deadlineAt()).isCloseTo(Instant.now().plus(Duration.ofMillis(DEADLINE_MS)), within(5, ChronoUnit.SECONDS));
        assertThat(view.status()).isEqualTo(IntegrationTaskStatus.PROCESSING);
        assertThat(view.deadlineAt()).isEqualTo(task.deadlineAt());
        assertThat(handler.events).containsExactly("prepare");
        verify(worker, never()).submit(any());

        // Act
        TransactionSimulation.commit();

        // Assert
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<AsyncTaskRun> submitted = ArgumentCaptor.forClass(AsyncTaskRun.class);
        verify(worker).submit(submitted.capture());
        assertThat(submitted.getValue().task().getTaskId()).isEqualTo(view.id());
        assertThat(submitted.getValue().deadlineAt()).isEqualTo(task.deadlineAt());
        assertThat(submitted.getValue().callTimeoutMs()).isEqualTo(CONNECT_MS + READ_MS);
        assertThat(submitted.getValue().payload()).isEqualTo(Map.of("code", "DH1"));
    }

    @Test
    void asyncRollbackSendsNothing() {
        // Arrange
        AsyncIntegrationDispatcher dispatcher = dispatcher(true);
        TransactionSimulation.begin();
        dispatcher.dispatch(submit(), handler, Map.of("code", "DH1"));

        // Act
        TransactionSimulation.rollback();

        // Assert
        verify(worker, never()).submit(any());
    }

    @Test
    void asyncDuplicateIsInProgress() {
        // Arrange
        AsyncIntegrationDispatcher dispatcher = dispatcher(true);
        TransactionSimulation.begin();
        doThrow(ObjectErrors.integrationTaskInProgress(OBJECT, orderId, new RuntimeException("23505")))
                .when(store).insert(any());

        // Act / Assert
        assertThatThrownBy(() -> dispatcher.dispatch(submit(), handler, Map.of("code", "DH1")))
                .isInstanceOf(IntegrationTaskInProgressException.class)
                .hasFieldOrPropertyWithValue("code.code", "integration.task.in_progress");
        TransactionSimulation.commit();
        verify(worker, never()).submit(any());
    }

    @Test
    void asyncRedispatchAfterAFinishedTaskStartsANewTask() {
        // Arrange: the first task ended (FAILED or UNCONFIRMED); only a task PROCESSING blocks, in the table
        AsyncIntegrationDispatcher dispatcher = dispatcher(true);

        // Act
        IntegrationTaskView first = dispatcher.dispatch(submit(), handler, Map.of("code", "DH1"));
        IntegrationTaskView second = dispatcher.dispatch(submit(), handler, Map.of("code", "DH1"));

        // Assert
        ArgumentCaptor<NewIntegrationTask> inserted = ArgumentCaptor.forClass(NewIntegrationTask.class);
        verify(store, times(2)).insert(inserted.capture());
        assertThat(inserted.getAllValues()).extracting(NewIntegrationTask::recordId).containsOnly(orderId);
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(handler.events).containsExactly("prepare", "prepare");
    }

    @Test
    void asyncWithoutRecordIdIsRefused() {
        // Arrange
        AsyncIntegrationDispatcher dispatcher = dispatcher(true);
        ActionContext<Map<String, Object>> bulk = ActionContext.<Map<String, Object>>builder().resource(OBJECT).action("submit").build();

        // Act / Assert
        assertThatThrownBy(() -> dispatcher.dispatch(bulk, handler, Map.of("code", "DH1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needs a record id");
        verifyNoInteractions(store);
    }
}
