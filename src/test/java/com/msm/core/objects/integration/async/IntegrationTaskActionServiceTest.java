package com.msm.core.objects.integration.async;

import com.msm.core.exceptions.FieldValueInvalidException;
import com.msm.core.exceptions.FieldValueRequiredException;
import com.msm.core.objects.audit.changelog.ManualChangeLog;
import com.msm.core.objects.config.IntegrationProperties;
import com.msm.core.objects.entity.metadata.IntegrationTaskMeta;
import com.msm.core.objects.exception.IntegrationTaskStateException;
import com.msm.core.objects.integration.data.AsyncProperties;
import com.msm.core.objects.integration.data.ConnectorProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IntegrationTaskActionServiceTest {

    private static final long DEADLINE_MS = 360_000L;

    private final IntegrationTaskStore store = mock(IntegrationTaskStore.class);
    private final AsyncIntegrationWorker worker = mock(AsyncIntegrationWorker.class);
    private final ManualChangeLog manualChangeLog = mock(ManualChangeLog.class);
    private final RecordingHandler handler = new RecordingHandler();

    @AfterEach
    void tearDown() {
        TransactionSimulation.end();
    }

    private IntegrationTaskActionService service(boolean idempotent) {
        AsyncProperties async = new AsyncProperties();
        async.setEnabled(true);
        async.setDeadlineMs(DEADLINE_MS);
        async.setIdempotent(idempotent);
        ConnectorProperties connector = new ConnectorProperties();
        connector.setAsync(async);
        IntegrationProperties properties = new IntegrationProperties();
        properties.getConnectors().put(RecordingHandler.CONNECTOR, connector);
        AsyncIntegrationHandlerRegistry registry = new AsyncIntegrationHandlerRegistry(() -> List.of(handler));
        registry.afterSingletonsInstantiated();
        return new IntegrationTaskActionService(store, registry, worker, new IntegrationCallRunner(new IntegrationOutcomeClassifier()),
                properties, manualChangeLog);
    }

    private IntegrationTaskRow task(IntegrationTaskStatus status) {
        Instant now = Instant.now();
        return new IntegrationTaskRow(UUID.randomUUID(), RecordingHandler.CONNECTOR, "v2order", UUID.randomUUID(),
                RecordingHandler.CONNECTOR, status, null, UUID.randomUUID(), 1, "{\"code\":\"DH1\"}", "{\"target\":\"CANCELED\"}",
                null, null, null, null, null, now, now.plusSeconds(360), null, "user", now, now, 3);
    }

    @Test
    void retryOfAnIdempotentConnectorSendsTheStoredRequestAfterCommit() {
        // Arrange
        IntegrationTaskRow row = task(IntegrationTaskStatus.UNCONFIRMED);
        when(store.findById(row.id())).thenReturn(Optional.of(row));
        when(store.restart(eq(row.id()), any(), any(), any(), eq("v2order"), eq(row.recordId()))).thenReturn(1);
        TransactionSimulation.begin();

        // Act
        Map<String, Object> view = service(true).retry(row.id());

        // Assert
        assertThat(view).containsEntry("status", "PROCESSING").containsEntry("id", row.id());
        verify(worker, never()).submit(any());
        TransactionSimulation.commit();
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<AsyncTaskRun> run = ArgumentCaptor.forClass(AsyncTaskRun.class);
        verify(worker).submit(run.capture());
        IntegrationTaskContext sent = run.getValue().task();
        assertThat(run.getValue().payload()).isEqualTo(Map.of("code", "DH1"));
        assertThat(sent.getAttemptCount()).isEqualTo(2);
        assertThat(sent.getAttemptId()).isNotEqualTo(row.attemptId());
        ArgumentCaptor<UUID> attempt = ArgumentCaptor.forClass(UUID.class);
        verify(store).restart(eq(row.id()), attempt.capture(), any(), any(), any(), any());
        assertThat(sent.getAttemptId()).isEqualTo(attempt.getValue());
        verify(manualChangeLog).update(eq(IntegrationTaskMeta.OBJECT_NAME), anyMap(), anyMap());
    }

    @Test
    void retryOfANonIdempotentConnectorIsRefused() {
        // Arrange
        IntegrationTaskRow row = task(IntegrationTaskStatus.UNCONFIRMED);
        when(store.findById(row.id())).thenReturn(Optional.of(row));

        // Act / Assert
        assertThatThrownBy(() -> service(false).retry(row.id()))
                .isInstanceOf(IntegrationTaskStateException.class)
                .hasFieldOrPropertyWithValue("code.code", "integration.task.unconfirmed");
        verify(store, never()).restart(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(worker);
    }

    @Test
    void retryNeedsAnUnconfirmedTask() {
        // Arrange
        IntegrationTaskRow row = task(IntegrationTaskStatus.FAILED);
        when(store.findById(row.id())).thenReturn(Optional.of(row));

        // Act / Assert
        assertThatThrownBy(() -> service(true).retry(row.id()))
                .isInstanceOf(IntegrationTaskStateException.class)
                .hasFieldOrPropertyWithValue("code.code", "integration.task.invalid_status");
    }

    @Test
    void retryLosingTheRaceIsInvalidStatus() {
        // Arrange
        IntegrationTaskRow row = task(IntegrationTaskStatus.UNCONFIRMED);
        when(store.findById(row.id())).thenReturn(Optional.of(row));
        when(store.restart(any(), any(), any(), any(), any(), any())).thenReturn(0);

        // Act / Assert
        assertThatThrownBy(() -> service(true).retry(row.id()))
                .hasFieldOrPropertyWithValue("code.code", "integration.task.invalid_status");
        verifyNoInteractions(worker);
    }

    @Test
    void resolveNeedsAReason() {
        assertThatThrownBy(() -> service(false).resolve(UUID.randomUUID(), Map.of("status", "SUCCEEDED")))
                .isInstanceOf(FieldValueRequiredException.class);
        verifyNoInteractions(store);
    }

    @Test
    void resolveNeedsSucceededOrFailed() {
        assertThatThrownBy(() -> service(false).resolve(UUID.randomUUID(), Map.of("status", "PROCESSING", "reason", "checked")))
                .isInstanceOf(FieldValueInvalidException.class);
        verifyNoInteractions(store);
    }

    @Test
    void resolveSucceededGivesTheDataToTheHandler() {
        // Arrange
        IntegrationTaskRow row = task(IntegrationTaskStatus.UNCONFIRMED);
        Map<String, Object> data = Map.of("HeaderId", 300100, "OrderNumber", "SO1");
        when(store.findById(row.id())).thenReturn(Optional.of(row));
        when(store.resolve(row.id(), IntegrationTaskStatus.SUCCEEDED, data, "checked on ERP", "SYSTEM")).thenReturn(1);
        TransactionSimulation.begin();

        // Act
        Map<String, Object> view = service(false).resolve(row.id(), Map.of("status", "SUCCEEDED", "reason", "checked on ERP", "data", data));

        // Assert
        assertThat(view).containsEntry("status", "SUCCEEDED");
        assertThat(handler.events).containsExactly("onSucceeded");
        assertThat(handler.lastResponse).isEqualTo(data);
        assertThat(handler.tasks.get(0).getTaskId()).isEqualTo(row.id());
        verify(manualChangeLog).update(eq(IntegrationTaskMeta.OBJECT_NAME), anyMap(), anyMap());
        TransactionSimulation.commit();
        assertThat(handler.events).containsExactly("onSucceeded", "afterSucceededCommit");
    }

    @Test
    void resolveFailedGivesTheReasonToTheHandler() {
        // Arrange
        IntegrationTaskRow row = task(IntegrationTaskStatus.UNCONFIRMED);
        when(store.findById(row.id())).thenReturn(Optional.of(row));
        when(store.resolve(eq(row.id()), eq(IntegrationTaskStatus.FAILED), any(), eq("not on ERP"), any())).thenReturn(1);

        // Act
        service(false).resolve(row.id(), Map.of("status", "FAILED", "reason", "not on ERP"));

        // Assert
        assertThat(handler.events).containsExactly("onFailed");
        assertThat(handler.lastError.code()).isEqualTo(IntegrationOutcome.RESOLVED.name());
        assertThat(handler.lastError.message()).isEqualTo("not on ERP");
    }

    @Test
    void resolveNeedsAnUnconfirmedTask() {
        // Arrange
        IntegrationTaskRow row = task(IntegrationTaskStatus.SUCCEEDED);
        when(store.findById(row.id())).thenReturn(Optional.of(row));

        // Act / Assert
        assertThatThrownBy(() -> service(false).resolve(row.id(), Map.of("status", "FAILED", "reason", "x")))
                .hasFieldOrPropertyWithValue("code.code", "integration.task.invalid_status");
        assertThat(handler.events).isEmpty();
    }
}
