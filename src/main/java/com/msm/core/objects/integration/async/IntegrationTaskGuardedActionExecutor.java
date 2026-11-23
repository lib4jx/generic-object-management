package com.msm.core.objects.integration.async;

import com.msm.core.action.context.ActionContext;
import com.msm.core.action.executor.ActionExecutor;

/**
 * The {@link ActionExecutor} of the generic routes (portal and internal) with {@link IntegrationTaskGuard} in front.
 * Only the outermost execution of the thread is checked: the request. Executions nested in a handler or a hook are
 * code of the service, like a write through {@code ObjectQueryRepository}, and would otherwise lock on the task the
 * same action has just created.
 */
public class IntegrationTaskGuardedActionExecutor implements ActionExecutor {

    private final ActionExecutor delegate;
    private final IntegrationTaskGuard guard;
    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

    public IntegrationTaskGuardedActionExecutor(ActionExecutor delegate, IntegrationTaskGuard guard) {
        this.delegate = delegate;
        this.guard = guard;
    }

    @Override
    public <T, X> T execute(ActionContext<X> request) {
        int level = depth.get();
        if (level == 0) {
            guard.check(request);
        }
        depth.set(level + 1);
        try {
            return delegate.execute(request);
        } finally {
            if (level == 0) {
                depth.remove();
            } else {
                depth.set(level);
            }
        }
    }
}
