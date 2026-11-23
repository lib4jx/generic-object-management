package com.msm.core.objects.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationPropertiesTest {

    private IntegrationProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("integration", IntegrationProperties.class);
    }

    @Test
    void connectorWithoutNewBlocksBindsAsBefore() {
        // Arrange / Act
        IntegrationProperties properties = bind(Map.of("integration.connectors.wms.base-url", "https://wms"));

        // Assert
        assertThat(properties.getConnectors().get("wms").getTimeout()).isNull();
        assertThat(properties.getConnectors().get("wms").getAsync()).isNull();
        assertThat(properties.getConnectors().get("wms").asyncEnabled()).isFalse();
        assertThat(properties.hasAsyncConnector()).isFalse();
        assertThat(properties.getAsyncExecutor().getMaxConcurrency()).isEqualTo(10);
        assertThat(properties.getAsyncExecutor().getQueueCapacity()).isEqualTo(100);
        assertThat(properties.getAsyncExecutor().getSweepIntervalMs()).isEqualTo(60_000L);
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void timeoutAndAsyncBlocksBind() {
        // Arrange / Act
        IntegrationProperties properties = bind(Map.of(
                "integration.async-executor.max-concurrency", "4",
                "integration.connectors.erp.timeout.connect-ms", "10000",
                "integration.connectors.erp.timeout.read-ms", "300000",
                "integration.connectors.erp.async.enabled", "true",
                "integration.connectors.erp.async.deadline-ms", "360000",
                "integration.connectors.erp.async.idempotent", "true"));

        // Assert
        assertThat(properties.getConnectors().get("erp").getTimeout().getConnectMs()).isEqualTo(10_000L);
        assertThat(properties.getConnectors().get("erp").readTimeoutMs()).isEqualTo(300_000L);
        assertThat(properties.getConnectors().get("erp").asyncEnabled()).isTrue();
        assertThat(properties.getConnectors().get("erp").idempotent()).isTrue();
        assertThat(properties.getAsyncExecutor().getMaxConcurrency()).isEqualTo(4);
        assertThat(properties.hasAsyncConnector()).isTrue();
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void asyncEnabledWithoutDeadlineFails() {
        // Arrange
        IntegrationProperties properties = bind(Map.of("integration.connectors.erp.async.enabled", "true"));

        // Act / Assert
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integration.connectors.erp.async.deadline-ms is required");
    }

    @Test
    void idempotentWithoutDeadlineFails() {
        // Arrange
        IntegrationProperties properties = bind(Map.of("integration.connectors.erp.async.idempotent", "true"));

        // Act / Assert
        assertThatThrownBy(properties::validate).hasMessageContaining("async.deadline-ms is required");
    }

    @Test
    void deadlineNotAboveReadTimeoutFails() {
        // Arrange
        IntegrationProperties properties = bind(Map.of(
                "integration.connectors.erp.timeout.read-ms", "300000",
                "integration.connectors.erp.async.enabled", "true",
                "integration.connectors.erp.async.deadline-ms", "300000"));

        // Act / Assert
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deadline-ms (300000) must be greater than timeout.read-ms (300000)");
    }

    @Test
    void disabledAsyncNeedsNoDeadline() {
        // Arrange
        IntegrationProperties properties = bind(Map.of("integration.connectors.erp.async.enabled", "false"));

        // Act / Assert
        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.hasAsyncConnector()).isFalse();
    }

    @Test
    void nonPositiveTimeoutFails() {
        // Arrange
        IntegrationProperties properties = bind(Map.of("integration.connectors.erp.timeout.read-ms", "0"));

        // Act / Assert
        assertThatThrownBy(properties::validate).hasMessageContaining("integration.connectors.erp.timeout.read-ms must be > 0");
    }
}
