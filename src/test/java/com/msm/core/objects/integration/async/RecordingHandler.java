package com.msm.core.objects.integration.async;

import com.msm.core.action.context.ActionContext;
import com.msm.core.objects.integration.ConnectorNamed;
import com.msm.core.objects.integration.ConnectorRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Test handler: records the steps it is asked for, behaviours set by the test. */
class RecordingHandler implements AsyncIntegrationHandler<Map<String, Object>, Map<String, Object>> {

    static final String CONNECTOR = "erp";

    final List<String> events = new CopyOnWriteArrayList<>();
    final List<IntegrationTaskContext> tasks = new CopyOnWriteArrayList<>();
    Function<Map<String, Object>, Object> partner = payload -> Map.of("HeaderId", 1);
    BusinessOutcome business = BusinessOutcome.accepted();
    Optional<BusinessOutcome> errorBusiness = Optional.empty();
    Optional<Object> alreadyDoneData = Optional.empty();
    RuntimeException onFailedError;
    RuntimeException onUnconfirmedError;
    RuntimeException onSucceededError;
    Object lastResponse;
    IntegrationError lastError;
    private final String action;

    RecordingHandler() {
        this(CONNECTOR);
    }

    RecordingHandler(String action) {
        this.action = action;
    }

    @Override
    public ConnectorNamed connector() {
        return () -> CONNECTOR;
    }

    @Override
    public String action() {
        return action;
    }

    @Override
    public PreparedCall<Map<String, Object>> prepare(ActionContext<?> context, Map<String, Object> input) {
        events.add("prepare");
        return PreparedCall.of(Map.of("code", input.get("code")), Map.of("target", "CANCELED"));
    }

    @Override
    public Object call(ConnectorRequestBuilder client, Map<String, Object> payload) {
        events.add("call");
        return partner.apply(payload);
    }

    @Override
    public BusinessOutcome classify(Object response) {
        events.add("classify");
        return business;
    }

    @Override
    public Optional<BusinessOutcome> classifyError(int status, String body) {
        events.add("classifyError");
        return errorBusiness;
    }

    @Override
    public void onSucceeded(IntegrationTaskContext task, Object response) {
        events.add("onSucceeded");
        tasks.add(task);
        lastResponse = response;
        if (onSucceededError != null) {
            throw onSucceededError;
        }
    }

    @Override
    public void onFailed(IntegrationTaskContext task, IntegrationError error) {
        events.add("onFailed");
        tasks.add(task);
        lastError = error;
        if (onFailedError != null) {
            throw onFailedError;
        }
    }

    @Override
    public void onUnconfirmed(IntegrationTaskContext task, IntegrationError error) {
        events.add("onUnconfirmed");
        tasks.add(task);
        lastError = error;
        if (onUnconfirmedError != null) {
            throw onUnconfirmedError;
        }
    }

    @Override
    public Optional<Object> onAlreadyDone(IntegrationTaskContext task, IntegrationError error) {
        events.add("onAlreadyDone");
        return alreadyDoneData;
    }

    @Override
    public void afterSucceededCommit(IntegrationTaskContext task) {
        events.add("afterSucceededCommit");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public Class<Map<String, Object>> payloadType() {
        return (Class) Map.class;
    }
}
