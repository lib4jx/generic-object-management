package com.msm.core.objects.integration.async;

import com.msm.core.action.context.ActionContext;
import com.msm.core.action.executor.ActionExecutor;
import com.msm.core.commons.Constants;
import com.msm.core.objects.entity.metadata.IntegrationTaskMeta;
import com.msm.core.objects.exception.IntegrationTaskInProgressException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IntegrationTaskGuardTest {

    private static final String OBJECT = "v2order";

    private final IntegrationTaskStore store = mock(IntegrationTaskStore.class);
    private final UUID orderId = UUID.randomUUID();

    private ActionContext<Object> action(String action, Object objectId, Object payload) {
        return ActionContext.builder().resource(OBJECT).action(action).objectId(objectId).payload(payload).build();
    }

    private IntegrationTaskRow processing(UUID recordId) {
        return new IntegrationTaskRow(UUID.randomUUID(), "erp", OBJECT, recordId, "erp", IntegrationTaskStatus.PROCESSING,
                null, null, null, null, null, null, null, null, null, null, null, null, 0);
    }

    @Test
    void offEverywhereReadsNothing() {
        // Arrange
        IntegrationTaskGuard guard = new IntegrationTaskGuard(store, false);

        // Act
        guard.check(action(Constants.Action.UPDATE, orderId, Map.of()));
        guard.check(OBJECT, orderId);

        // Assert
        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @ValueSource(strings = {Constants.FilterAction.FILTER_OBJECT, Constants.FilterAction.FILTER_OBJECT_BY_ID,
            Constants.FilterAction.FILTER_ALL_OBJECT, Constants.FilterAction.FILTER_ALL_OBJECT_BY_IDS,
            Constants.FilterAction.LOOKUP_OBJECT, Constants.Action.CREATE, Constants.Action.BULK_CREATE})
    void readsAndCreationsAreNeverChecked(String actionName) {
        // Arrange
        IntegrationTaskGuard guard = new IntegrationTaskGuard(store, true);

        // Act
        guard.check(action(actionName, orderId, List.of(Map.of(Constants.OBJECT_PK, orderId.toString()))));

        // Assert
        verifyNoInteractions(store);
    }

    @Test
    void updateOfARecordWithATaskInProgressIsRefused() {
        // Arrange
        IntegrationTaskGuard guard = new IntegrationTaskGuard(store, true);
        when(store.findProcessing(OBJECT, List.of(orderId))).thenReturn(Optional.of(processing(orderId)));

        // Act / Assert
        assertThatThrownBy(() -> guard.check(action(Constants.Action.UPDATE, orderId, Map.of())))
                .isInstanceOf(IntegrationTaskInProgressException.class)
                .hasFieldOrPropertyWithValue("code.code", "integration.task.in_progress")
                .satisfies(error -> assertThat(((IntegrationTaskInProgressException) error).getParams())
                        .containsEntry(IntegrationTaskInProgressException.RECORD_ID_PARAM, orderId));
    }

    @Test
    void customActionWithoutTaskPasses() {
        // Arrange
        IntegrationTaskGuard guard = new IntegrationTaskGuard(store, true);
        when(store.findProcessing(anyString(), any())).thenReturn(Optional.empty());

        // Act
        guard.check(action("cancel", orderId, Map.of()));

        // Assert
        verify(store).findProcessing(OBJECT, List.of(orderId));
    }

    @Test
    void bulkWritesCheckTheIdsOfThePayload() {
        // Arrange
        IntegrationTaskGuard guard = new IntegrationTaskGuard(store, true);
        UUID other = UUID.randomUUID();
        when(store.findProcessing(anyString(), any())).thenReturn(Optional.empty());

        // Act
        guard.check(action(Constants.Action.BULK_UPDATE, null,
                List.of(Map.of(Constants.OBJECT_PK, orderId.toString()), Map.of(Constants.OBJECT_PK, other), Map.of("code", "x"))));

        // Assert
        verify(store).findProcessing(OBJECT, List.of(orderId, other));
    }

    @Test
    void idsThatAreNotUuidsCannotHaveATask() {
        // Arrange
        IntegrationTaskGuard guard = new IntegrationTaskGuard(store, true);

        // Act
        guard.check(action(Constants.Action.DELETE, "not-a-uuid", Map.of()));

        // Assert
        verifyNoInteractions(store);
    }

    @Test
    void theTaskObjectAndNamedQueriesAreNotChecked() {
        // Arrange
        IntegrationTaskGuard guard = new IntegrationTaskGuard(store, true);

        // Act
        guard.check(ActionContext.builder().resource(IntegrationTaskMeta.OBJECT_NAME).action(IntegrationTaskMeta.ACTION_RESOLVE)
                .objectId(orderId).build());
        guard.check(ActionContext.builder().resource(Constants.RESOURCE_QUERY_PREFIX).action("orderSummary").objectId(orderId).build());

        // Assert
        verifyNoInteractions(store);
    }

    @Test
    void onlyTheOutermostExecutionIsChecked() {
        // Arrange
        IntegrationTaskGuard guard = mock(IntegrationTaskGuard.class);
        ActionExecutor delegate = mock(ActionExecutor.class);
        IntegrationTaskGuardedActionExecutor executor = new IntegrationTaskGuardedActionExecutor(delegate, guard);
        ActionContext<Object> outer = action("submit", orderId, Map.of());
        ActionContext<Object> nested = action(Constants.Action.UPDATE, orderId, Map.of());
        when(delegate.execute(eq(outer))).thenAnswer(call -> executor.execute(nested));
        when(delegate.execute(eq(nested))).thenReturn("updated");

        // Act
        Object result = executor.execute(outer);
        executor.execute(nested);

        // Assert
        assertThat(result).isEqualTo("updated");
        verify(guard, times(1)).check(outer);
        verify(guard, times(1)).check(nested);
    }
}
