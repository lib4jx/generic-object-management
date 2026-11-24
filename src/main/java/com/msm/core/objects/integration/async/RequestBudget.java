package com.msm.core.objects.integration.async;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * How long an inline wait may last within the request (since 2.7.8): {@code async.request-budget-ms} minus the time
 * already spent since the request reached the service ({@link RequestStartFilter}), capped by
 * {@code async.inline-wait-ms}. Without a budget, or outside an HTTP request (job, async hook), the wait is
 * {@code inline-wait-ms}.
 */
class RequestBudget {

    private final LongSupplier nanoTime;
    private final Supplier<Long> requestStartNanos;

    RequestBudget() {
        this(System::nanoTime, RequestBudget::currentRequestStartNanos);
    }

    RequestBudget(LongSupplier nanoTime, Supplier<Long> requestStartNanos) {
        this.nanoTime = nanoTime;
        this.requestStartNanos = requestStartNanos;
    }

    /** Milliseconds spent since the request reached the service; null outside an HTTP request. */
    Long elapsedMs() {
        Long startNanos = requestStartNanos.get();
        return startNanos == null ? null : TimeUnit.NANOSECONDS.toMillis(nanoTime.getAsLong() - startNanos);
    }

    /**
     * The wait for a call: {@code min(inlineWaitMs, budgetMs - elapsed)}; {@code inlineWaitMs} without a budget or a
     * request. 0 or less: no time left in the request, the call must not be started.
     */
    long waitMs(long inlineWaitMs, Long budgetMs, Long elapsedMs) {
        if (budgetMs == null || elapsedMs == null) {
            return inlineWaitMs;
        }
        return Math.min(inlineWaitMs, budgetMs - elapsedMs);
    }

    private static Long currentRequestStartNanos() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        Object startNanos = attributes.getAttribute(RequestStartFilter.START_NANOS_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return startNanos instanceof Long nanos ? nanos : null;
    }
}
