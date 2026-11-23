package com.msm.core.objects.integration.async;

import com.msm.core.objects.integration.data.AsyncExecutorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncIntegrationSweeperTest {

    private final IntegrationTaskStore store = mock(IntegrationTaskStore.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final RecordingHandler handler = new RecordingHandler();
    private AsyncIntegrationSweeper sweeper;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        AsyncIntegrationHandlerRegistry registry = new AsyncIntegrationHandlerRegistry(() -> List.of(handler));
        registry.afterSingletonsInstantiated();
        sweeper = new AsyncIntegrationSweeper(store, registry, new IntegrationCallRunner(new IntegrationOutcomeClassifier()),
                new AsyncExecutorProperties(), transactionManager);
    }

    static IntegrationTaskRow expired(String connector, String action) {
        Instant now = Instant.now();
        return new IntegrationTaskRow(UUID.randomUUID(), connector, "v2order", UUID.randomUUID(), action,
                IntegrationTaskStatus.UNCONFIRMED, IntegrationOutcome.DEADLINE_EXCEEDED.name(), UUID.randomUUID(), 1,
                "{\"code\":\"DH1\"}", "{\"target\":\"CANCELED\"}", null, IntegrationOutcome.DEADLINE_EXCEEDED.name(), "late",
                null, null, now.minusSeconds(400), now.minusSeconds(40), now, "user", now.minusSeconds(400), now, 1);
    }

    @Test
    void everyExpiredTaskIsUnconfirmedOnceAndAFailingCallbackDoesNotStopTheRound() {
        // Arrange
        IntegrationTaskRow failing = expired(RecordingHandler.CONNECTOR, RecordingHandler.CONNECTOR);
        IntegrationTaskRow ok = expired(RecordingHandler.CONNECTOR, RecordingHandler.CONNECTOR);
        List<Set<UUID>> exclusions = new ArrayList<>();
        when(store.expireNext(any(), anyString())).thenAnswer(call -> {
            Collection<UUID> excluded = call.getArgument(0);
            exclusions.add(Set.copyOf(excluded));
            if (exclusions.size() == 1) {
                handler.onUnconfirmedError = new IllegalStateException("order locked");
                return Optional.of(failing);
            }
            handler.onUnconfirmedError = null;
            return exclusions.size() == 2 ? Optional.of(ok) : Optional.empty();
        });

        // Act
        int closed = sweeper.sweepOnce();

        // Assert
        assertThat(closed).isEqualTo(1);
        assertThat(exclusions).containsExactly(Set.of(), Set.of(failing.id()), Set.of(failing.id()));
        assertThat(handler.events).containsExactly("onUnconfirmed", "onUnconfirmed");
        assertThat(handler.lastError.code()).isEqualTo(IntegrationOutcome.DEADLINE_EXCEEDED.name());
        assertThat(handler.tasks.get(1).getPayloadAs(Map.class)).containsEntry("code", "DH1");
        assertThat(handler.tasks.get(1).getContextValue("target", String.class)).isEqualTo("CANCELED");
        ArgumentCaptor<TransactionStatus> committed = ArgumentCaptor.forClass(TransactionStatus.class);
        verify(transactionManager, times(3)).commit(committed.capture());
        // the transaction of the failing callback is marked rollback-only: its task goes back to PROCESSING
        assertThat(committed.getAllValues()).extracting(TransactionStatus::isRollbackOnly).containsExactly(true, false, false);
    }

    @Test
    void taskWithoutHandlerStaysUnconfirmed() {
        // Arrange
        when(store.expireNext(any(), anyString()))
                .thenReturn(Optional.of(expired("gone", "gone")))
                .thenReturn(Optional.empty());

        // Act
        int closed = sweeper.sweepOnce();

        // Assert
        assertThat(closed).isEqualTo(1);
        assertThat(handler.events).isEmpty();
        verify(transactionManager, times(2)).commit(any());
    }
}
