package com.msm.core.objects.integration.async;

import com.msm.core.objects.integration.ConnectorRequestBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Steps shared by the sync path of the dispatcher, the worker, the sweeper and the action {@code resolve}: call and
 * classify, settle ALREADY_DONE, hand the result to the handler.
 */
@Slf4j
@RequiredArgsConstructor
public class IntegrationCallRunner {

    private final IntegrationOutcomeClassifier classifier;

    /** Calls the partner and classifies the answer or the error; ALREADY_DONE is settled with {@code onAlreadyDone}. */
    public <P> ClassifiedOutcome call(AsyncIntegrationHandler<?, P> handler, IntegrationTaskContext task, P payload,
                                      ConnectorRequestBuilder client) {
        ClassifiedOutcome outcome;
        try {
            Object response = handler.call(client, payload);
            outcome = classifier.classifyResponse(handler, response);
        } catch (RuntimeException e) {
            // whatever the call throws is a result to classify (IO, status, mapping); the client and integration_log
            // already logged it, the handler gets it as IntegrationError.cause
            outcome = classifier.classifyError(handler, e);
        }
        return outcome.isAlreadyDone() ? settleAlreadyDone(handler, task, outcome) : outcome;
    }

    /** onSucceeded / onFailed / onUnconfirmed by the status of the outcome. */
    public void deliver(AsyncIntegrationHandler<?, ?> handler, IntegrationTaskContext task, ClassifiedOutcome outcome) {
        switch (outcome.status()) {
            case SUCCEEDED -> handler.onSucceeded(task, outcome.response());
            case FAILED -> handler.onFailed(task, outcome.error());
            case UNCONFIRMED -> handler.onUnconfirmed(task, outcome.error());
            case PROCESSING -> throw new IllegalStateException("A call result cannot be PROCESSING");
        }
    }

    /** {@code afterSucceededCommit}, its errors logged: the success is committed and stays. */
    public void afterSucceededCommit(AsyncIntegrationHandler<?, ?> handler, IntegrationTaskContext task) {
        try {
            handler.afterSucceededCommit(task);
        } catch (RuntimeException e) {
            log.error("afterSucceededCommit of {} failed for {} {} (task {})", handler.action(), task.getObjectName(),
                    task.getRecordId(), task.getTaskId(), e);
        }
    }

    private ClassifiedOutcome settleAlreadyDone(AsyncIntegrationHandler<?, ?> handler, IntegrationTaskContext task,
                                                ClassifiedOutcome outcome) {
        IntegrationError error = outcome.error();
        Optional<Object> data = handler.onAlreadyDone(task, error);
        if (data.isPresent()) {
            return ClassifiedOutcome.succeeded(IntegrationOutcome.ALREADY_DONE, data.get());
        }
        log.warn("{} of {} {} already done at the partner, without its data: UNCONFIRMED", handler.action(),
                task.getObjectName(), task.getRecordId());
        return ClassifiedOutcome.unconfirmed(IntegrationOutcome.ALREADY_DONE,
                error.withMessage("Already done at the partner, its data is missing: " + error.message()));
    }
}
