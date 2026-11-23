package com.msm.core.objects.integration.middleware;

import com.msm.core.objects.entity.metadata.IntegrationLogMeta;
import com.msm.core.objects.integration.context.HttpRequestContext;
import com.msm.core.objects.service.IntegrationLogService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TracingMiddlewareTest {

    private final IntegrationLogService integrationLogService = mock(IntegrationLogService.class);
    private final TracingMiddleware middleware = new TracingMiddleware(integrationLogService);

    @SuppressWarnings("unchecked")
    private Map<String, Object> written() {
        ArgumentCaptor<Map<String, Object>> log = ArgumentCaptor.forClass(Map.class);
        verify(integrationLogService).createIntegrationLog(log.capture());
        return log.getValue();
    }

    @Test
    void callOfATaskIsLoggedWithTheTaskIdAndTheReadTimeout() {
        // Arrange
        UUID taskId = UUID.randomUUID();
        HttpRequestContext context = HttpRequestContext.builder().connectorName("erp").method(HttpMethod.POST)
                .baseUrl("https://erp").path("/so").timeoutMs(300_000L).build();
        context.put(HttpRequestContext.ATTRIBUTE_ASYNC_TASK_ID, taskId);

        // Act
        middleware.beforeExecute(context);
        middleware.afterExecute(context, Map.of("ok", true));

        // Assert
        Map<String, Object> log = written();
        assertThat(log).containsEntry(IntegrationLogMeta.CORRELATION_ID.getFieldName(), taskId.toString());
        assertThat(log).containsEntry(IntegrationLogMeta.TIMEOUT_MS.getFieldName(), 300_000L);
    }

    @Test
    void errorBeforeTheLogRecordStillCarriesTheTaskFields() {
        // Arrange
        UUID taskId = UUID.randomUUID();
        HttpRequestContext context = HttpRequestContext.builder().connectorName("erp").method(HttpMethod.POST)
                .baseUrl("https://erp").path("/so").timeoutMs(1_000L).build();
        context.put(HttpRequestContext.ATTRIBUTE_ASYNC_TASK_ID, taskId);

        // Act
        middleware.onErrorExecute(context, new IllegalStateException("auth failed"));

        // Assert
        assertThat(written()).containsEntry(IntegrationLogMeta.CORRELATION_ID.getFieldName(), taskId.toString())
                .containsEntry(IntegrationLogMeta.TIMEOUT_MS.getFieldName(), 1_000L);
    }

    @Test
    void plainCallHasNoCorrelationId() {
        // Arrange
        HttpRequestContext context = HttpRequestContext.builder().connectorName("wms").method(HttpMethod.GET)
                .baseUrl("https://wms").path("/x").build();

        // Act
        middleware.beforeExecute(context);
        middleware.afterExecute(context, Map.of());

        // Assert
        assertThat(written().get(IntegrationLogMeta.CORRELATION_ID.getFieldName())).isNull();
    }
}
