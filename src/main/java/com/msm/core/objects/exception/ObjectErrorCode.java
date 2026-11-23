package com.msm.core.objects.exception;


import com.msm.core.exceptions.common.ErrorCode;

public enum ObjectErrorCode implements ErrorCode {
    OBJECT_NOT_FOUND("OBJECT_NOT_FOUND"),
    INTERNAL_OBJECT_CLIENT("INTERNAL_OBJECT_CLIENT"),
    INTEGRATION_REQUEST_ERROR("INTEGRATION_REQUEST_ERROR"),
    AUTH_ERROR("AUTH_ERROR"),
    RATE_LIMIT("RATE_LIMIT"),
    PAYLOAD_INVALID("PAYLOAD_INVALID"),
    /** The record has an integration task PROCESSING: no write until it ends (HTTP 409). */
    INTEGRATION_TASK_IN_PROGRESS("integration.task.in_progress"),
    /** UNCONFIRMED task of a connector that is not idempotent: not sent again, to be resolved by hand. */
    INTEGRATION_TASK_UNCONFIRMED("integration.task.unconfirmed"),
    /** The task is not in the status the action needs (retry / resolve need UNCONFIRMED). */
    INTEGRATION_TASK_INVALID_STATUS("integration.task.invalid_status"),
    /** The action waited {@code async.inline-wait-ms} without a result; the task goes on in the background (HTTP 409). */
    INTEGRATION_TASK_PROCESSING("integration.task.processing");

    private final String code;

    ObjectErrorCode(String code) {
        this.code = code;
    }

    public String getCode() {
        return this.code;
    }
}
