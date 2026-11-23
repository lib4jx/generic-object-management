package com.msm.core.objects.integration.async;

import java.time.Instant;
import java.util.UUID;

// Row inserted by the dispatcher, status PROCESSING; request / context as JSON text.
public record NewIntegrationTask(UUID id, String connector, String objectName, UUID recordId, String action,
                                 UUID attemptId, String request, String context, Instant startedAt, Instant deadlineAt,
                                 String createdBy) {
}
