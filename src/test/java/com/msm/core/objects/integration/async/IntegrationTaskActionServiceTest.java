package com.msm.core.objects.integration.async;

import com.msm.core.exceptions.FieldValueInvalidException;
import com.msm.core.exceptions.FieldValueRequiredException;
import com.msm.core.objects.audit.changelog.ManualChangeLog;
import com.msm.core.objects.entity.metadata.IntegrationTaskMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IntegrationTaskActionServiceTest {

    private final IntegrationTaskStore store = mock(IntegrationTaskStore.class);
    private final ManualChangeLog manualChangeLog = mock(ManualChangeLog.class);
    private final RecordingHandler handler = new RecordingHandler();

    @AfterEach
    void tearDown() {
        TransactionSimulation.end();
    }

    private IntegrationTaskActionService service() {
        AsyncIntegrationHandlerRegistry registry = new AsyncIntegrationHandlerRegistry(() -> List.of(handler));
        registry.afterSingletonsInstantiated();
        return new IntegrationTaskActionService(store, registry, new IntegrationCallRunner(new IntegrationOutcomeClassifier()),
                manualChangeLog);
    }

    private IntegrationTaskRow task(IntegrationTaskStatus status) {
        Instant now = Instant.now();
        return new IntegrationTaskRow(UUID.randomUUID(), RecordingHandler.CONNECTOR, "v2order", UUID.randomUUID(),
                RecordingHandler.CONNECTOR, status, null, UUID.randomUUID(), 1, "{\"target\":\"CANCELED\"}",
                null, null, null, null, now, now.plusSeconds(360), null, "user", now, now, 3);
    }

    @Test
    void resolveNeedsAReason() {
        assertThatThrownBy(() -> service().resolve(UUID.randomUUID(), Map.of("status", "SUCCEEDED")))
                .isInstanceOf(FieldValueRequiredException.class);
        verifyNoInteractions(store);
    }

    @Test
    void resolveNeedsSucceededOrFailed() {
        assertThatThrownBy(() -> service().resolve(UUID.randomUUID(), Map.of("status", "PROCESSING", "reason", "checked")))
                .isInstanceOf(FieldValueInvalidException.class);
        verifyNoInteractions(store);
    }

    @Test
    void resolveSucceededGivesTheDataToTheHandler() {
        // Arrange
        IntegrationTaskRow row = task(IntegrationTaskStatus.UNCONFIRMED);
        Map<String, Object> data = Map.of("HeaderId", 300100, "OrderNumber", "SO1");
        when(store.findById(row.id())).thenReturn(Optional.of(row));
        when(store.resolve(row.id(), IntegrationTaskStatus.SUCCEEDED, "checked on ERP", "SYSTEM")).thenReturn(1);
        TransactionSimulation.begin();

        // Act
        Map<String, Object> view = service().resolve(row.id(), Map.of("status", "SUCCEEDED", "reason", "checked on ERP", "data", data));

        // Assert
        assertThat(view).containsEntry("status", "SUCCEEDED");
        assertThat(handler.events).containsExactly("onSucceeded");
        assertThat(handler.lastResponse).isEqualTo(data);
        assertThat(handler.tasks.get(0).getTaskId()).isEqualTo(row.id());
        assertThat(handler.tasks.get(0).getContextValue("target", String.class)).isEqualTo("CANCELED");
        assertThat(handler.tasks.get(0).getPayload()).isNull();
        verify(manualChangeLog).update(eq(IntegrationTaskMeta.OBJECT_NAME), anyMap(), anyMap());
        TransactionSimulation.commit();
        assertThat(handler.events).containsExactly("onSucceeded", "afterSucceededCommit");
    }

    @Test
    void resolveFailedGivesTheReasonToTheHandler() {
        // Arrange
        IntegrationTaskRow row = task(IntegrationTaskStatus.UNCONFIRMED);
        when(store.findById(row.id())).thenReturn(Optional.of(row));
        when(store.resolve(eq(row.id()), eq(IntegrationTaskStatus.FAILED), eq("not on ERP"), any())).thenReturn(1);

        // Act
        service().resolve(row.id(), Map.of("status", "FAILED", "reason", "not on ERP"));

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
        assertThatThrownBy(() -> service().resolve(row.id(), Map.of("status", "FAILED", "reason", "x")))
                .hasFieldOrPropertyWithValue("code.code", "integration.task.invalid_status");
        assertThat(handler.events).isEmpty();
    }
}
