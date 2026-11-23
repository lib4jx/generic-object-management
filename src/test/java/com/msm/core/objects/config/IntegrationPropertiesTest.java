package com.msm.core.objects.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationPropertiesTest {

    private IntegrationProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("integration", IntegrationProperties.class);
    }

    /** A valid async connector {@code erp} (read timeout 300s, deadline 360s) plus {@code extra}. */
    private Map<String, String> asyncErp(Map<String, String> extra) {
        Map<String, String> values = new HashMap<>(extra);
        values.put("integration.connectors.erp.timeout.read-ms", "300000");
        values.put("integration.connectors.erp.async.enabled", "true");
        values.put("integration.connectors.erp.async.deadline-ms", "360000");
        return values;
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
    void inlineWaitDefaultsToZeroAndBinds() {
        // Arrange / Act
        IntegrationProperties defaults = bind(asyncErp(Map.of()));
        IntegrationProperties inline = bind(asyncErp(Map.of("integration.connectors.erp.async.inline-wait-ms", "25000")));

        // Assert
        assertThat(defaults.getConnectors().get("erp").getAsync().getInlineWaitMs()).isZero();
        assertThat(inline.getConnectors().get("erp").getAsync().getInlineWaitMs()).isEqualTo(25_000L);
        assertThatCode(defaults::validate).doesNotThrowAnyException();
        assertThatCode(inline::validate).doesNotThrowAnyException();
    }

    @Test
    void asyncEnabledWithoutReadTimeoutFails() {
        // Arrange
        IntegrationProperties properties = bind(Map.of(
                "integration.connectors.erp.async.enabled", "true",
                "integration.connectors.erp.async.deadline-ms", "360000"));

        // Act / Assert
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integration.connectors.erp.timeout.read-ms is required when async.enabled is true");
    }

    @Test
    void idempotentWithoutAsyncNeedsNoReadTimeout() {
        // Arrange
        IntegrationProperties properties = bind(Map.of(
                "integration.connectors.erp.async.idempotent", "true",
                "integration.connectors.erp.async.deadline-ms", "360000"));

        // Act / Assert
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void inlineWaitNotBelowDeadlineFails() {
        // Arrange
        IntegrationProperties properties = bind(asyncErp(Map.of("integration.connectors.erp.async.inline-wait-ms", "360000")));

        // Act / Assert
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inline-wait-ms (360000) must be lower than async.deadline-ms (360000)");
    }

    @Test
    void negativeInlineWaitFails() {
        // Arrange
        IntegrationProperties properties = bind(asyncErp(Map.of("integration.connectors.erp.async.inline-wait-ms", "-1")));

        // Act / Assert
        assertThatThrownBy(properties::validate).hasMessageContaining("integration.connectors.erp.async.inline-wait-ms must be >= 0");
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
