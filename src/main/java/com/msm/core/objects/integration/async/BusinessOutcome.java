package com.msm.core.objects.integration.async;

/**
 * What the handler reads in the partner's answer ({@link AsyncIntegrationHandler#classify} / {@code classifyError}).
 *
 * @param kind    accepted, refused or already done
 * @param code    business code of a refusal, written to {@code integration_task.error_code}
 * @param message text of a refusal / already done
 */
public record BusinessOutcome(Kind kind, String code, String message) {

    public enum Kind {
        /** The partner did it. */
        ACCEPTED,
        /** The partner refused it: the task is FAILED. */
        REJECTED,
        /** The partner did it before (a previous sending): see {@link AsyncIntegrationHandler#onAlreadyDone}. */
        ALREADY_DONE
    }

    public static BusinessOutcome accepted() {
        return new BusinessOutcome(Kind.ACCEPTED, null, null);
    }

    public static BusinessOutcome rejected(String code, String message) {
        return new BusinessOutcome(Kind.REJECTED, code, message);
    }

    public static BusinessOutcome alreadyDone(String message) {
        return new BusinessOutcome(Kind.ALREADY_DONE, IntegrationOutcome.ALREADY_DONE.name(), message);
    }
}
