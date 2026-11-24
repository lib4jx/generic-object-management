package com.msm.core.objects.integration.async;

import java.time.Instant;

// The sending of a task given to the worker: the handler, the task, the typed payload, the task's deadline_at and the
// longest the call may take (connect + read timeout of the connector), to not send a call that could end past deadline_at.
public record AsyncTaskRun<P>(AsyncIntegrationHandler<?, P> handler, IntegrationTaskContext task, P payload,
                              Instant deadlineAt, long callTimeoutMs) {
}
