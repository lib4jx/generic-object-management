package com.msm.core.objects.integration.async;

/** Column {@code integration_task.outcome}: why the task ended in its status (design §6). */
public enum IntegrationOutcome {
    /** 2xx and the handler accepted the answer. */
    ACCEPTED,
    /** The handler read a business refusal (2xx or error status). */
    REJECTED,
    /** The partner says it was done already. */
    ALREADY_DONE,
    /** 4xx other than 408 / 429, or 401 once the token was renewed. */
    HTTP_ERROR,
    /** 429 after the retries. */
    RATE_LIMITED,
    /** No connection (refused, DNS, connect timeout): the partner did not get the request. */
    NOT_SENT,
    /** Read timeout, connection reset, 408 or 5xx: the partner may have got the request. */
    NO_RESPONSE,
    /** Any other error of the call. */
    UNKNOWN_ERROR,
    /** {@code integrationTaskExecutor} full: not sent. */
    EXECUTOR_FULL,
    /**
     * Not sent: when a thread took the task (after waiting in the {@code integrationTaskExecutor} queue), the call
     * (connect + read timeout) could no longer end before {@code deadline_at}, when the sweeper releases the record.
     */
    EXPIRED_BEFORE_SEND,
    /** Still PROCESSING at {@code deadline_at} (instance stopped, result lost). */
    DEADLINE_EXCEEDED,
    /** Closed by hand with the action {@code resolve}. */
    RESOLVED,
    /**
     * Inline wait: the partner succeeded but the action's transaction rolled back, so the record does not show it; the
     * task is UNCONFIRMED; the partner's answer is in {@code integration_log}.
     */
    ACTION_ROLLED_BACK
}
