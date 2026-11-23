package com.msm.core.objects.integration;

import com.msm.core.objects.config.IntegrationProperties;
import com.msm.core.objects.integration.data.ConnectorProperties;
import com.msm.core.objects.integration.data.TimeoutProperties;
import com.msm.core.objects.integration.factory.RestClientFactory;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectorRequestClientRegistryTest {

    @Test
    void connectorWithTimeoutHasItsOwnClientOthersTheSharedOne() {
        // Arrange
        ConnectorProperties erp = new ConnectorProperties();
        TimeoutProperties timeout = new TimeoutProperties();
        timeout.setReadMs(300_000L);
        erp.setTimeout(timeout);
        IntegrationProperties properties = new IntegrationProperties();
        properties.getConnectors().put("erp", erp);
        properties.getConnectors().put("wms", new ConnectorProperties());
        RequestClient shared = mock(RequestClient.class);
        RestClientFactory factory = mock(RestClientFactory.class);
        when(factory.create(any())).thenReturn(mock(RestClient.class));

        // Act
        ConnectorRequestClientRegistry registry = new ConnectorRequestClientRegistry(properties, shared, factory);

        // Assert
        assertThat(registry.get("erp")).isNotSameAs(shared).isInstanceOf(DefaultRequestClient.class);
        assertThat(registry.get("erp")).isSameAs(registry.get("erp"));
        assertThat(registry.hasOwnClient("erp")).isTrue();
        assertThat(registry.get("wms")).isSameAs(shared);
        assertThat(registry.get("unknown")).isSameAs(shared);
        verify(factory, times(1)).create(erp);
    }

    @Test
    void sharedOnlyRegistryKeepsTheOldBehaviour() {
        // Arrange
        RequestClient shared = mock(RequestClient.class);

        // Act
        ConnectorRequestClientRegistry registry = new ConnectorRequestClientRegistry(shared);

        // Assert
        assertThat(registry.get("erp")).isSameAs(shared);
        assertThat(registry.hasOwnClient("erp")).isFalse();
    }
}
