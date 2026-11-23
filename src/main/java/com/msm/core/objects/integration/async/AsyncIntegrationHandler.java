package com.msm.core.objects.integration.async;

import com.msm.core.action.context.ActionContext;
import com.msm.core.objects.integration.ConnectorNamed;
import com.msm.core.objects.integration.ConnectorRequestBuilder;

import java.util.Optional;

/**
 * One kind of call to a partner, made through {@link AsyncIntegrationDispatcher#dispatch} at the place the action used
 * to call the partner. Three steps: {@link #prepare} in the action's transaction, {@link #call} outside any
 * transaction, then the result callbacks in a new transaction (async) or in the action's transaction (sync).
 *
 * <p>Declare implementations as Spring beans: the sweeper and the actions {@code retry} / {@code resolve} find them
 * by {@code connector().getName()} + {@link #action()}, the pair stored on the task.
 *
 * @param <I> data the action gives at the call place (e.g. the order record)
 * @param <P> payload sent to the partner, stored as JSON in {@code integration_task.request}
 */
public interface AsyncIntegrationHandler<I, P> {

    ConnectorNamed connector();

    /** Name of the call, stored in {@code integration_task.action}; unique per connector. Default: the connector name. */
    default String action() {
        return connector().getName();
    }

    /**
     * Step 1, in the action's transaction: validate, build the payload. Never call the partner here. Data the result
     * step needs (e.g. the target status of a cancel) goes in {@link PreparedCall#context()}.
     */
    PreparedCall<P> prepare(ActionContext<?> context, I input);

    /** Step 2, outside any transaction: only the call to the partner. Its errors are classified, not propagated. */
    Object call(ConnectorRequestBuilder client, P payload);

    /** The partner answered 2xx: accepted or refused. */
    BusinessOutcome classify(Object response);

    /**
     * The partner answered an error status: recognise errors with a business meaning, e.g. "already exists".
     * Empty: the default table applies (design §6).
     */
    default Optional<BusinessOutcome> classifyError(int status, String body) {
        return Optional.empty();
    }

    /** Step 3: update the record with the answer (or the data of {@link #onAlreadyDone} / of {@code resolve}). */
    void onSucceeded(IntegrationTaskContext task, Object response);

    /** Step 3: the partner refused it or certainly did not get it. In sync mode, throw the action's own error here. */
    void onFailed(IntegrationTaskContext task, IntegrationError error);

    /** Step 3: no certain result. In sync mode, throw the action's own error here. */
    void onUnconfirmed(IntegrationTaskContext task, IntegrationError error);

    /**
     * The partner says it was done already. Return the data of what was done (e.g. read the sales order by its source
     * number) to end the task SUCCEEDED through {@link #onSucceeded}; empty: the task is UNCONFIRMED.
     * Called outside any transaction, so it may call the partner.
     */
    default Optional<Object> onAlreadyDone(IntegrationTaskContext task, IntegrationError error) {
        return Optional.empty();
    }

    /**
     * After the transaction that wrote SUCCEEDED committed (sync: the request's transaction): work that needs the
     * partner's result and must not roll back with the record, e.g. sending the order to another system. Errors are
     * logged, never propagated.
     */
    default void afterSucceededCommit(IntegrationTaskContext task) {
    }

    /** Type of the payload, to read {@code request} back when the task is sent again. */
    Class<P> payloadType();
}
