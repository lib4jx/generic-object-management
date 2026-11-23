package com.msm.core.objects.integration.async;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Who handles the result of an inline-wait call: the request (INLINE) or the worker (DETACHED). The first side to
 * leave PENDING decides, once: the request when its wait ends, the completion of the call when the result arrives.
 * Exactly one side handles the result, even when it arrives as the wait ends.
 */
final class InlineDelivery {

    private enum State { PENDING, INLINE, DETACHED }

    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);

    /** The result is there: true when the request handles it, false when the request already detached (worker). */
    boolean resultForRequest() {
        return state.compareAndSet(State.PENDING, State.INLINE) || state.get() == State.INLINE;
    }

    /** The wait is over without a result: true when the worker handles it, false when it arrived first (request). */
    boolean detach() {
        return state.compareAndSet(State.PENDING, State.DETACHED);
    }
}
