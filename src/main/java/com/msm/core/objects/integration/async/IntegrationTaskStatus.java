package com.msm.core.objects.integration.async;

/** Column {@code integration_task.status}. */
public enum IntegrationTaskStatus {
    /** Sent or about to be sent; the record is locked. */
    PROCESSING,
    SUCCEEDED,
    FAILED,
    /** No certain result (no answer, 5xx, deadline passed): the partner may have done it. */
    UNCONFIRMED
}
