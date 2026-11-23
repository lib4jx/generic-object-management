package com.msm.core.objects.integration.async;

/**
 * Result of a call once classified: the status the task gets, why, and the answer (SUCCEEDED) or the error.
 * {@link IntegrationOutcome#ALREADY_DONE} comes out of the classifier as UNCONFIRMED until
 * {@link AsyncIntegrationHandler#onAlreadyDone} gives the data.
 *
 * @param status   status the task gets
 * @param outcome  why
 * @param response answer (or data) given to {@code onSucceeded}; null otherwise
 * @param error    error given to {@code onFailed} / {@code onUnconfirmed}; null when SUCCEEDED
 */
public record ClassifiedOutcome(IntegrationTaskStatus status, IntegrationOutcome outcome, Object response,
                                IntegrationError error) {

    public static ClassifiedOutcome succeeded(IntegrationOutcome outcome, Object response) {
        return new ClassifiedOutcome(IntegrationTaskStatus.SUCCEEDED, outcome, response, null);
    }

    public static ClassifiedOutcome failed(IntegrationOutcome outcome, IntegrationError error) {
        return new ClassifiedOutcome(IntegrationTaskStatus.FAILED, outcome, null, error);
    }

    public static ClassifiedOutcome unconfirmed(IntegrationOutcome outcome, IntegrationError error) {
        return new ClassifiedOutcome(IntegrationTaskStatus.UNCONFIRMED, outcome, null, error);
    }

    public boolean isAlreadyDone() {
        return outcome == IntegrationOutcome.ALREADY_DONE && status == IntegrationTaskStatus.UNCONFIRMED;
    }
}
