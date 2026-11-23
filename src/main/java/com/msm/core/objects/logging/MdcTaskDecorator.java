package com.msm.core.objects.logging;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Carries the logging context (MDC: {@code traceId}, {@code spanId}…) of the thread that hands a task to an executor
 * onto the thread that runs it, then puts the worker thread's own context back. Without it, everything a hook or an
 * integration task logs or writes to {@code integration_log} on a pool thread has no {@code traceId}.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> callerContext = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> workerContext = MDC.getCopyOfContextMap();
            setContext(callerContext);
            try {
                runnable.run();
            } finally {
                setContext(workerContext);
            }
        };
    }

    private void setContext(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
