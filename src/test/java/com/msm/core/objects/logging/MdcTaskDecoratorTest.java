package com.msm.core.objects.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MdcTaskDecoratorTest {

    private final MdcTaskDecorator decorator = new MdcTaskDecorator();

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void theTaskRunsWithTheTraceIdOfTheThreadThatHandedItOver() throws Exception {
        // Arrange
        MDC.put(LogTraceUtils.TRACE_ID_KEY, "trace-1");
        AtomicReference<String> seen = new AtomicReference<>();
        Runnable task = decorator.decorate(() -> seen.set(MDC.get(LogTraceUtils.TRACE_ID_KEY)));
        ExecutorService pool = Executors.newSingleThreadExecutor();

        // Act
        pool.submit(task).get();
        pool.shutdown();

        // Assert
        assertThat(seen.get()).isEqualTo("trace-1");
    }

    @Test
    void theWorkerThreadGetsItsOwnContextBackAfterTheTask() throws Exception {
        // Arrange
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.submit(() -> MDC.put(LogTraceUtils.TRACE_ID_KEY, "worker")).get();
        MDC.put(LogTraceUtils.TRACE_ID_KEY, "request");
        Runnable task = decorator.decorate(() -> { });

        // Act
        pool.submit(task).get();
        Future<String> after = pool.submit(() -> MDC.get(LogTraceUtils.TRACE_ID_KEY));

        // Assert
        assertThat(after.get()).isEqualTo("worker");
        pool.shutdown();
    }

    @Test
    void aCallerWithoutContextLeavesNoStaleTraceIdOnThePoolThread() throws Exception {
        // Arrange
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.submit(decorator.decorate(() -> MDC.put(LogTraceUtils.TRACE_ID_KEY, "leaked"))).get();
        AtomicReference<String> seen = new AtomicReference<>("not run");
        Runnable task = decorator.decorate(() -> seen.set(MDC.get(LogTraceUtils.TRACE_ID_KEY)));

        // Act
        pool.submit(task).get();
        pool.shutdown();

        // Assert
        assertThat(seen.get()).isNull();
    }
}
