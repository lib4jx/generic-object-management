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
 * The SQL of {@link IntegrationTaskStore}, the sweeper and {@link IntegrationTaskLogReader} against a real Postgres,
 * with the README migration and an {@code integration_log} partitioned on {@code created_at} as in orders (V100062).
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
                context         JSONB,
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

    /**
     * {@code integration_log} as in orders (V100020 columns read by the reader, partitioned on {@code created_at} as
     * V100062, index of the README), three quarter partitions.
     */
    private static final String LOG_DDL = """
            CREATE TABLE integration_log (
                id              UUID         NOT NULL DEFAULT gen_random_uuid(),
                created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
                completed_at    TIMESTAMPTZ,
                correlation_id  VARCHAR(300),
                status_code     INTEGER,
                status          VARCHAR(50),
                timeout_ms      BIGINT,
                error_code      VARCHAR(100),
                error_message   TEXT,
                request_body    TEXT,
                response_body   TEXT
            ) PARTITION BY RANGE (created_at);
            CREATE TABLE integration_log_2026q1 PARTITION OF integration_log FOR VALUES FROM ('2026-01-01Z') TO ('2026-04-01Z');
            CREATE TABLE integration_log_2026q2 PARTITION OF integration_log FOR VALUES FROM ('2026-04-01Z') TO ('2026-07-01Z');
            CREATE TABLE integration_log_2026q3 PARTITION OF integration_log FOR VALUES FROM ('2026-07-01Z') TO ('2026-10-01Z');
            CREATE INDEX IF NOT EXISTS idx_integration_log_correlation_id
                ON integration_log (correlation_id) WHERE correlation_id IS NOT NULL;
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
        dsl.execute("drop table if exists integration_log");
        dsl.execute(LOG_DDL);
        store = new IntegrationTaskStore(dsl);
    }

    private NewIntegrationTask newTask(UUID recordId, Instant deadlineAt) {
        Instant now = Instant.now();
        return new NewIntegrationTask(UUID.randomUUID(), RecordingHandler.CONNECTOR, OBJECT, recordId, RecordingHandler.CONNECTOR,
                UUID.randomUUID(), "{\"target\":\"CANCELED\"}", now, deadlineAt, "user");
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
        assertThat(row.context()).isEqualTo("{\"target\": \"CANCELED\"}");
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
    void resultOfAnotherAttemptOrAfterTheSweepIsDropped() {
        // Arrange
        NewIntegrationTask task = newTask(UUID.randomUUID(), Instant.now().plusSeconds(360));
        store.insert(task);
        NewIntegrationTask late = newTask(UUID.randomUUID(), Instant.now().minusSeconds(1));
        store.insert(late);
        assertThat(store.expireNext(List.of(), "late")).map(IntegrationTaskRow::id).contains(late.id());
        ClassifiedOutcome succeeded = ClassifiedOutcome.succeeded(IntegrationOutcome.ACCEPTED, Map.of("HeaderId", 1));

        // Act
        int otherAttempt = store.finish(task.id(), UUID.randomUUID(), succeeded);
        int current = store.finish(task.id(), task.attemptId(), succeeded);
        int twice = store.finish(task.id(), task.attemptId(), succeeded);
        int afterSweep = store.finish(late.id(), late.attemptId(), succeeded);

        // Assert
        assertThat(otherAttempt).isZero();
        assertThat(current).isEqualTo(1);
        assertThat(twice).isZero();
        assertThat(afterSweep).isZero();
        IntegrationTaskRow row = store.findById(task.id()).orElseThrow();
        assertThat(row.status()).isEqualTo(IntegrationTaskStatus.SUCCEEDED);
        assertThat(row.outcome()).isEqualTo(IntegrationOutcome.ACCEPTED.name());
        assertThat(row.completedAt()).isNotNull();
        assertThat(row.version()).isEqualTo(1);
        assertThat(store.findById(late.id()).orElseThrow().status()).isEqualTo(IntegrationTaskStatus.UNCONFIRMED);
    }

    @Test
    void resolveOnlyClosesAnUnconfirmedTask() {
        // Arrange
        NewIntegrationTask task = newTask(UUID.randomUUID(), Instant.now().minusSeconds(1));
        store.insert(task);

        // Act
        int whileProcessing = store.resolve(task.id(), IntegrationTaskStatus.FAILED, "x", "admin");
        store.expireNext(List.of(), "late");
        int resolved = store.resolve(task.id(), IntegrationTaskStatus.SUCCEEDED, "checked", "admin");
        int again = store.resolve(task.id(), IntegrationTaskStatus.FAILED, "x", "admin");

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

    @Test
    void logsOfATaskAreItsCallsWithinItsWindowOldestFirst() {
        // Arrange: task 10:00:00 - 10:01:10 on 2026-05-10, window 09:59:55 - 10:02:10 (5s before, 1 min after)
        UUID taskId = UUID.randomUUID();
        Instant startedAt = Instant.parse("2026-05-10T10:00:00Z");
        Instant completedAt = Instant.parse("2026-05-10T10:01:10Z");
        IntegrationTaskRow task = taskRow(taskId, startedAt, completedAt);
        insertLog(taskId.toString(), "2026-05-10T10:00:40Z", 200, "{\"code\":\"DH1\"}", "{\"HeaderId\":1}");
        insertLog(taskId.toString(), "2026-05-10T10:00:01Z", 504, "{\"code\":\"DH1\"}", null);
        insertLog(taskId.toString(), "2026-05-10T10:01:14Z", 200, "{\"code\":\"DH1\"}", "{\"HeaderId\":2}");
        insertLog(taskId.toString(), "2026-05-10T09:59:54Z", 200, "before the window", null);
        insertLog(taskId.toString(), "2026-05-10T10:02:11Z", 200, "after the window", null);
        insertLog(taskId.toString(), "2026-02-01T10:00:00Z", 200, "other partition", null);
        insertLog(UUID.randomUUID().toString(), "2026-05-10T10:00:30Z", 200, "other task", null);
        IntegrationTaskLogReader reader = new IntegrationTaskLogReader(dsl);

        // Act
        List<IntegrationTaskLog> logs = reader.findLogs(task);

        // Assert
        assertThat(logs).extracting(IntegrationTaskLog::createdAt).containsExactly(Instant.parse("2026-05-10T10:00:01Z"),
                Instant.parse("2026-05-10T10:00:40Z"), Instant.parse("2026-05-10T10:01:14Z"));
        assertThat(logs).extracting(IntegrationTaskLog::statusCode).containsExactly(504, 200, 200);
        assertThat(logs.get(1).requestBody()).isEqualTo("{\"code\":\"DH1\"}");
        assertThat(logs.get(1).responseBody()).isEqualTo("{\"HeaderId\":1}");
        assertThat(logs.get(1).timeoutMs()).isEqualTo(300_000L);
    }

    @Test
    void logQueryScansOnlyThePartitionOfTheTaskWindow() {
        // Arrange
        IntegrationTaskRow task = taskRow(UUID.randomUUID(), Instant.parse("2026-05-10T10:00:00Z"),
                Instant.parse("2026-05-10T10:01:10Z"));
        IntegrationTaskLogReader reader = new IntegrationTaskLogReader(dsl);

        // Act
        String plan = dsl.fetch("explain (costs off) " + dsl.renderInlined(reader.logsQuery(task))).format();

        // Assert
        assertThat(plan).contains("integration_log_2026q2")
                .doesNotContain("integration_log_2026q1")
                .doesNotContain("integration_log_2026q3");
    }

    private IntegrationTaskRow taskRow(UUID id, Instant startedAt, Instant completedAt) {
        return new IntegrationTaskRow(id, RecordingHandler.CONNECTOR, OBJECT, UUID.randomUUID(), RecordingHandler.CONNECTOR,
                IntegrationTaskStatus.SUCCEEDED, IntegrationOutcome.ACCEPTED.name(), UUID.randomUUID(), 1, null, null, null,
                null, null, startedAt, startedAt.plusSeconds(360), completedAt, "user", startedAt, completedAt, 1);
    }

    private void insertLog(String correlationId, String createdAt, int statusCode, String requestBody, String responseBody) {
        dsl.execute("insert into integration_log (created_at, completed_at, correlation_id, status_code, status, timeout_ms, "
                        + "request_body, response_body) values (cast(? as timestamptz), cast(? as timestamptz) + interval '1 second', "
                        + "?, ?, 'SUCCESS', 300000, ?, ?)",
                createdAt, createdAt, correlationId, statusCode, requestBody, responseBody);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
