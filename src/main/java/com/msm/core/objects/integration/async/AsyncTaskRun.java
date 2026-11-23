package com.msm.core.objects.integration.async;

// One sending of a task given to the worker: the handler, the task (with its attempt id) and the typed payload.
public record AsyncTaskRun<P>(AsyncIntegrationHandler<?, P> handler, IntegrationTaskContext task, P payload) {
}
