package com.msm.core.objects.integration.async;

/** Rebuilds the context of a stored task for its handler: payload read back with {@code payloadType()}. */
class TaskContextFactory {

    private final IntegrationTaskJson json = new IntegrationTaskJson();

    <P> AsyncTaskRun<P> runOf(AsyncIntegrationHandler<?, P> handler, IntegrationTaskRow row) {
        P payload = json.read(row.request(), handler.payloadType());
        IntegrationTaskContext task = IntegrationTaskContext.builder()
                .taskId(row.id())
                .connector(row.connector())
                .objectName(row.objectName())
                .recordId(row.recordId())
                .action(row.action())
                .attemptId(row.attemptId())
                .attemptCount(row.attemptCount())
                .payload(payload)
                .context(json.readMap(row.context()))
                .async(true)
                .build();
        return new AsyncTaskRun<>(handler, task, payload);
    }
}
