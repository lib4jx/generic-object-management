package com.msm.core.objects.integration.async;

/**
 * Rebuilds the context of a stored task for its handler's callbacks (sweeper, {@code resolve}): ids and
 * {@code context}; no payload, it is not stored.
 */
class TaskContextFactory {

    private final IntegrationTaskJson json = new IntegrationTaskJson();

    IntegrationTaskContext contextOf(IntegrationTaskRow row) {
        return IntegrationTaskContext.builder()
                .taskId(row.id())
                .connector(row.connector())
                .objectName(row.objectName())
                .recordId(row.recordId())
                .action(row.action())
                .context(json.readMap(row.context()))
                .async(true)
                .build();
    }
}
