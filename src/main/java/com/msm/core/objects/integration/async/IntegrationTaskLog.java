package com.msm.core.objects.integration.async;

import java.time.Instant;
import java.util.UUID;

/**
 * One call of a task to the partner, read from {@code integration_log} by {@link IntegrationTaskLogReader}: what was
 * sent and answered (or the error).
 */
public record IntegrationTaskLog(UUID id, Instant createdAt, Instant completedAt, Integer statusCode, String status,
                                 String requestBody, String responseBody, String errorCode, String errorMessage,
                                 Long timeoutMs) {
}
