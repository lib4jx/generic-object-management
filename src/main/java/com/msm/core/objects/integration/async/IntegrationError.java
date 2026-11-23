package com.msm.core.objects.integration.async;

/**
 * Why a call did not succeed, given to {@code onFailed} / {@code onUnconfirmed} / {@code onAlreadyDone}.
 *
 * @param code         business code of the handler, else the {@link IntegrationOutcome} name
 * @param message      what happened, for the log and {@code integration_task.error_message}
 * @param statusCode   HTTP status when the partner answered, else null
 * @param responseBody body of the error answer, else null
 * @param cause        exception of the call, null when there was none (business refusal in a 2xx, deadline, resolve)
 */
public record IntegrationError(String code, String message, Integer statusCode, String responseBody, Throwable cause) {

    public static IntegrationError of(IntegrationOutcome outcome, String message) {
        return new IntegrationError(outcome.name(), message, null, null, null);
    }

    public static IntegrationError of(IntegrationOutcome outcome, String message, Throwable cause) {
        return new IntegrationError(outcome.name(), message, null, null, cause);
    }

    public IntegrationError withMessage(String newMessage) {
        return new IntegrationError(code, newMessage, statusCode, responseBody, cause);
    }
}
