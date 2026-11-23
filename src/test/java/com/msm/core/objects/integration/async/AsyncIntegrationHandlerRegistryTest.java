package com.msm.core.objects.integration.async;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsyncIntegrationHandlerRegistryTest {

    @Test
    void handlersAreFoundByConnectorAndAction() {
        // Arrange
        RecordingHandler submit = new RecordingHandler("submit_so");
        RecordingHandler cancel = new RecordingHandler("cancel_so");
        AsyncIntegrationHandlerRegistry registry = new AsyncIntegrationHandlerRegistry(() -> List.of(submit, cancel));

        // Act
        registry.afterSingletonsInstantiated();

        // Assert
        assertThat(registry.find(RecordingHandler.CONNECTOR, "submit_so")).containsSame(submit);
        assertThat(registry.find(RecordingHandler.CONNECTOR, "cancel_so")).containsSame(cancel);
        assertThat(registry.find("other", "submit_so")).isEmpty();
    }

    @Test
    void twoHandlersForTheSamePairFailTheStartup() {
        // Arrange
        AsyncIntegrationHandlerRegistry registry = new AsyncIntegrationHandlerRegistry(
                () -> List.of(new RecordingHandler(), new RecordingHandler()));

        // Act / Assert
        assertThatThrownBy(registry::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Two AsyncIntegrationHandler for erp:erp");
    }
}
