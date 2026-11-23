package com.msm.core.objects.integration.async;

import com.msm.core.objects.exception.IntegrationTaskInProgressException;
import com.msm.core.objects.integration.data.AsyncExecutorProperties;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SQL of {@link IntegrationTaskStore} and the sweeper against a real Postgres, with the README migration.
 * Same database as {@code ChangeLogPostgresIT}:
 * {@code CHANGELOG_PG_URL=jdbc:postgresql://localhost:55432/changelog ./gradlew test --tests '*IntegrationTaskPostgresIT'}
 * (user {@code CHANGELOG_PG_USER}, default postgres; password {@code CHANGELOG_PG_PASSWORD}, default test).
 */
@EnabledIfEnvironmentVariable(named = "CHANGELOG_PG_URL", matches = ".+")
class IntegrationTaskPostgresIT {

    private static final String OBJECT = "v2order";
    private static final int TASKS = 20;

    /** Same statements as the README migration. */
    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS integration_task (
                id              UUID PRIMARY KEY,
                connector       VARCHAR(60)  NOT NULL,
                object_name     VARCHAR(60)  NOT NULL,
                record_id       UUID         NOT NULL,
                action          VARCHAR(60)  NOT NULL,
                status          VARCHAR(20)  NOT NULL,
                outcome         VARCHAR(20),
                attempt_id      UUID         NOT NULL,
                attempt_count   INT          NOT NULL DEFAULT 1,
                request         JSONB,
                context         JSONB,
                response        JSONB,
                error_code      VARCHAR(60),
                error_message   VARCHAR(1000),
                resolved_by     VARCHAR(100),
                resolve_reason  VARCHAR(1000),
                started_at      TIMESTAMPTZ  NOT NULL,
                deadline_at     TIMESTAMPTZ  NOT NULL,
                completed_at    TIMESTAMPTZ,
                created_by      VARCHAR(100),
                created_at      TIMESTAMPTZ  NOT NULL,
                updated_at      TIMESTAMPTZ,
                version         BIGINT       NOT NULL DEFAULT 0
            );
            CREATE UNIQUE INDEX IF NOT EXISTS uq_integration_task_processing
                ON integration_task (object_name, record_id) WHERE status = 'PROCESSING';
            CREATE INDEX IF NOT EXISTS idx_integration_task_record
                ON integration_task (object_name, record_id, created_at DESC);
            CREATE INDEX IF NOT EXISTS idx_integration_task_deadline
                ON integration_task (deadline_at) WHERE status = 'PROCESSING';
            """;

    private static DSLContext dsl;
    private static DataSourceTransactionManager transactionManager;
    private static TransactionTemplate tx;

    private IntegrationTaskStore store;

    @BeforeAll
    static void connect() {
        DriverManagerDataSource driver = new DriverManagerDataSource(System.getenv("CHANGELOG_PG_URL"),
                Optional.ofNullable(System.getenv("CHANGELOG_PG_USER")).orElse("postgres"),
                Optional.ofNullable(System.getenv("CHANGELOG_PG_PASSWORD")).orElse("test"));
        dsl = DSL.using(new TransactionAwareDataSourceProxy(driver), SQLDialect.POSTGRES);
        transactionManager = new DataSourceTransactionManager(driver);
        tx = new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void setUp() {
        dsl.execute("drop table if exists integration_task");
        dsl.execute(DDL);
        store = new IntegrationTaskStore(dsl);
    }

    private NewIntegrationTask newTask(UUID recordId, Instant deadlineAt) {
        Instant now = Instant.now();
        return new NewIntegrationTask(UUID.randomUUID(), RecordingHandler.CONNECTOR, OBJECT, recordId, RecordingHandler.CONNECTOR,
                UUID.randomUUID(), "{\"code\":\"DH1\"}", "{\"target\":\"CANCELED\"}", now, deadlineAt, "user");
    }

    @Test
    void secondTaskOfARecordIsInProgressAndTheRowIsReadBack() {
        // Arrange
        UUID recordId = UUID.randomUUID();
        NewIntegrationTask first = newTask(recordId, Instant.now().plusSeconds(360));
        tx.executeWithoutResult(status -> store.insert(first));

        // Act / Assert
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> store.insert(newTask(recordId, Instant.now().plusSeconds(360)))))
                .isInstanceOf(IntegrationTaskInProgressException.class);
        IntegrationTaskRow row = store.findById(first.id()).orElseThrow();
        assertThat(row.status()).isEqualTo(IntegrationTaskStatus.PROCESSING);
        assertThat(row.attemptCount()).isEqualTo(1);
        assertThat(row.request()).isEqualTo("{\"code\": \"DH1\"}");
        assertThat(store.findProcessing(OBJECT, List.of(UUID.randomUUID(), recordId))).map(IntegrationTaskRow::id).contains(first.id());
        assertThat(store.findProcessing("other", List.of(recordId))).isEmpty();
    }

    @Test
    void finishedTaskDoesNotBlockANewOneOfTheRecord() {
        // Arrange: one task FAILED, one UNCONFIRMED (deadline)
        UUID recordId = UUID.randomUUID();
        NewIntegrationTask failed = newTask(recordId, Instant.now().plusSeconds(360));
        store.insert(failed);
        store.finish(failed.id(), failed.attemptId(), ClassifiedOutcome.failed(IntegrationOutcome.HTTP_ERROR,
                IntegrationError.of(IntegrationOutcome.HTTP_ERROR, "bad")));
        NewIntegrationTask unconfirmed = newTask(recordId, Instant.now().minusSeconds(1));
        store.insert(unconfirmed);
        assertThat(store.expireNext(List.of(), "late")).isPresent();
        NewIntegrationTask next = newTask(recordId, Instant.now().plusSeconds(360));

        // Act
        store.insert(next);

        // Assert
        assertThat(store.findById(failed.id()).orElseThrow().status()).isEqualTo(IntegrationTaskStatus.FAILED);
        assertThat(store.findById(unconfirmed.id()).orElseThrow().status()).isEqualTo(IntegrationTaskStatus.UNCONFIRMED);
        assertThat(store.findProcessing(OBJECT, List.of(recordId))).map(IntegrationTaskRow::id).contains(next.id());
    }

    @Test
    void taskInsertedInItsOwnTransactionOutlivesTheCallersRollback() {
        // Arrange: inline wait inserts with REQUIRES_NEW inside the action's transaction
        UUID recordId = UUID.randomUUID();
        NewIntegrationTask task = newTask(recordId, Instant.now().plusSeconds(360));
        TransactionTemplate newTransaction = new TransactionTemplate(transactionManager);
        newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Act
        tx.executeWithoutResult(action -> {
            newTransaction.executeWithoutResult(status -> store.insert(task));
            action.setRollbackOnly();
        });

        // Assert: still PROCESSING, so another request of the record is in progress
        assertThat(store.findById(task.id()).orElseThrow().status()).isEqualTo(IntegrationTaskStatus.PROCESSING);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> store.insert(newTask(recordId, Instant.now().plusSeconds(360)))))
                .isInstanceOf(IntegrationTaskInProgressException.class);
    }

    @Test
    void resultOfAnOldAttemptCannotOverwriteTheTask() {
        // Arrange: sent with A1, closed by the sweeper, sent again with A2
        NewIntegrationTask task = newTask(UUID.randomUUID(), Instant.now().minusSeconds(1));
        store.insert(task);
        assertThat(store.expireNext(List.of(), "late")).isPresent();
        UUID secondAttempt = UUID.randomUUID();
        assertThat(store.restart(task.id(), secondAttempt, Instant.now(), Instant.now().plusSeconds(360), OBJECT, task.recordId())).isEqualTo(1);
        ClassifiedOutcome succeeded = ClassifiedOutcome.succeeded(IntegrationOutcome.ACCEPTED, Map.of("HeaderId", 1));

        // Act
        int stale = store.finish(task.id(), task.attemptId(), succeeded);
        int current = store.finish(task.id(), secondAttempt, succeeded);
        int twice = store.finish(task.id(), secondAttempt, succeeded);

        // Assert
        assertThat(stale).isZero();
        assertThat(current).isEqualTo(1);
        assertThat(twice).isZero();
        IntegrationTaskRow row = store.findById(task.id()).orElseThrow();
        assertThat(row.status()).isEqualTo(IntegrationTaskStatus.SUCCEEDED);
        assertThat(row.attemptCount()).isEqualTo(2);
        assertThat(row.response()).isEqualTo("{\"HeaderId\": 1}");
        assertThat(row.version()).isEqualTo(3);
    }

    @Test
    void retryWhileANewTaskRunsIsInProgress() {
        // Arrange
        UUID recordId = UUID.randomUUID();
        NewIntegrationTask old = newTask(recordId, Instant.now().minusSeconds(1));
        store.insert(old);
        store.expireNext(List.of(), "late");
        store.insert(newTask(recordId, Instant.now().plusSeconds(360)));

        // Act / Assert
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> store.restart(old.id(), UUID.randomUUID(), Instant.now(),
                Instant.now().plusSeconds(360), OBJECT, recordId)))
                .isInstanceOf(IntegrationTaskInProgressException.class);
    }

    @Test
    void resolveOnlyClosesAnUnconfirmedTask() {
        // Arrange
        NewIntegrationTask task = newTask(UUID.randomUUID(), Instant.now().minusSeconds(1));
        store.insert(task);

        // Act
        int whileProcessing = store.resolve(task.id(), IntegrationTaskStatus.FAILED, null, "x", "admin");
        store.expireNext(List.of(), "late");
        int resolved = store.resolve(task.id(), IntegrationTaskStatus.SUCCEEDED, Map.of("HeaderId", 9), "checked", "admin");
        int again = store.resolve(task.id(), IntegrationTaskStatus.FAILED, null, "x", "admin");

        // Assert
        assertThat(whileProcessing).isZero();
        assertThat(resolved).isEqualTo(1);
        assertThat(again).isZero();
        IntegrationTaskRow row = store.findById(task.id()).orElseThrow();
        assertThat(row.status()).isEqualTo(IntegrationTaskStatus.SUCCEEDED);
        assertThat(row.outcome()).isEqualTo(IntegrationOutcome.RESOLVED.name());
        assertThat(row.resolvedBy()).isEqualTo("admin");
        assertThat(row.resolveReason()).isEqualTo("checked");
    }

    @Test
    void twoConcurrentSweepsCloseEachExpiredTaskOnce() throws Exception {
        // Arrange
        List<UUID> expired = new ArrayList<>();
        for (int i = 0; i < TASKS; i++) {
            NewIntegrationTask task = newTask(UUID.randomUUID(), Instant.now().minusSeconds(1));
            store.insert(task);
            expired.add(task.id());
        }
        NewIntegrationTask running = newTask(UUID.randomUUID(), Instant.now().plusSeconds(360));
        store.insert(running);
        Map<UUID, AtomicInteger> calls = new ConcurrentHashMap<>();
        RecordingHandler handler = new RecordingHandler() {
            @Override
            public void onUnconfirmed(IntegrationTaskContext task, IntegrationError error) {
                calls.computeIfAbsent(task.getTaskId(), id -> new AtomicInteger()).incrementAndGet();
                sleepBriefly();
            }
        };
        AsyncIntegrationHandlerRegistry registry = new AsyncIntegrationHandlerRegistry(() -> List.of(handler));
        registry.afterSingletonsInstantiated();
        IntegrationCallRunner runner = new IntegrationCallRunner(new IntegrationOutcomeClassifier());
        AsyncIntegrationSweeper first = new AsyncIntegrationSweeper(store, registry, runner, new AsyncExecutorProperties(), transactionManager);
        AsyncIntegrationSweeper second = new AsyncIntegrationSweeper(store, registry, runner, new AsyncExecutorProperties(), transactionManager);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        // Act
        Future<Integer> firstClosed = threads.submit(() -> {
            start.await();
            return first.sweepOnce();
        });
        Future<Integer> secondClosed = threads.submit(() -> {
            start.await();
            return second.sweepOnce();
        });
        start.countDown();
        int closed = firstClosed.get(30, TimeUnit.SECONDS) + secondClosed.get(30, TimeUnit.SECONDS);
        threads.shutdown();

        // Assert
        assertThat(closed).isEqualTo(TASKS);
        assertThat(calls.keySet()).containsExactlyInAnyOrderElementsOf(expired);
        assertThat(calls.values()).allSatisfy(count -> assertThat(count.get()).isEqualTo(1));
        assertThat(store.findById(running.id()).orElseThrow().status()).isEqualTo(IntegrationTaskStatus.PROCESSING);
        assertThat(expired).allSatisfy(id -> assertThat(store.findById(id).orElseThrow().status()).isEqualTo(IntegrationTaskStatus.UNCONFIRMED));
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
