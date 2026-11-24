package com.msm.core.objects.integration.async;

import java.time.Instant;
import java.util.UUID;

// Row inserted by the dispatcher, status PROCESSING; context as JSON text (the payload is not stored).
public record NewIntegrationTask(UUID id, String connector, String objectName, UUID recordId, String action,
                                 String context, Instant startedAt, Instant deadlineAt, String createdBy) {
}
