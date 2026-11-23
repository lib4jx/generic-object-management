package com.msm.core.objects.integration.async;

import com.msm.core.objects.config.IntegrationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Matches when at least one {@code integration.connectors.<name>.async.enabled} is true. */
public class OnAsyncIntegrationCondition extends SpringBootCondition {

    private static final String PREFIX = "integration";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        IntegrationProperties properties = Binder.get(context.getEnvironment())
                .bind(PREFIX, IntegrationProperties.class)
                .orElseGet(IntegrationProperties::new);
        return properties.hasAsyncConnector()
                ? ConditionOutcome.match("a connector has async.enabled")
                : ConditionOutcome.noMatch("no connector has async.enabled");
    }
}
