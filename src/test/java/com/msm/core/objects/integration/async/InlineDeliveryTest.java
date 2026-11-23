package com.msm.core.objects.integration.async;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class InlineDeliveryTest {

    private static final int ROUNDS = 2_000;

    @Test
    void requestThatDetachedFirstLeavesTheResultToTheWorker() {
        // Arrange
        InlineDelivery delivery = new InlineDelivery();

        // Act
        boolean detached = delivery.detach();
        boolean forRequest = delivery.resultForRequest();

        // Assert
        assertThat(detached).isTrue();
        assertThat(forRequest).isFalse();
    }

    @Test
    void resultThatArrivedFirstStaysWithTheRequest() {
        // Arrange
        InlineDelivery delivery = new InlineDelivery();

        // Act: completion of the call, then the end of the wait, then the request reads it
        boolean completionSide = delivery.resultForRequest();
        boolean detached = delivery.detach();
        boolean requestSide = delivery.resultForRequest();

        // Assert
        assertThat(completionSide).isTrue();
        assertThat(detached).isFalse();
        assertThat(requestSide).isTrue();
    }

    @Test
    void concurrentEndOfWaitAndResultDeliverExactlyOnce() throws Exception {
        // Arrange
        ExecutorService threads = Executors.newFixedThreadPool(2);
        int workerDeliveries = 0;
        int requestDeliveries = 0;
        try {
            for (int round = 0; round < ROUNDS; round++) {
                InlineDelivery delivery = new InlineDelivery();
                CountDownLatch start = new CountDownLatch(1);

                // Act: the call completes while the request's wait ends
                Future<Boolean> completion = threads.submit(() -> {
                    start.await();
                    return !delivery.resultForRequest();
                });
                Future<Boolean> request = threads.submit(() -> {
                    start.await();
                    return !delivery.detach();
                });
                start.countDown();
                boolean worker = completion.get();
                boolean inRequest = request.get();

                // Assert: one side, never both, never none
                assertThat(worker).isNotEqualTo(inRequest);
                workerDeliveries += worker ? 1 : 0;
                requestDeliveries += inRequest ? 1 : 0;
            }
        } finally {
            threads.shutdownNow();
        }
        assertThat(workerDeliveries + requestDeliveries).isEqualTo(ROUNDS);
    }
}
