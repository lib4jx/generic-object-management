package com.msm.core.objects.integration;

import com.msm.core.objects.config.IntegrationProperties;
import com.msm.core.objects.exception.integration.IntegrationRequestException;
import com.msm.core.objects.integration.context.HttpRequestContext;
import com.msm.core.objects.integration.data.ConnectorProperties;
import com.msm.core.objects.integration.data.retry.RetryDefaultProperties;
import com.msm.core.objects.integration.middleware.HttpMiddlewareChain;
import com.msm.core.objects.integration.retry.ExchangeRetryExecutor;
import com.msm.core.objects.integration.retry.HandleRequestRetryExecutor;
import com.msm.core.objects.integration.retry.RetryConfigResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** H4: the exchange retry only renews the token; async calls are not sent again once they may have reached the partner. */
class IntegrationClientExchangeRetryTest {

    private static final String CONNECTOR = "erp";
    private static final int MAX_ATTEMPTS = 3;

    private final RequestClient requestClient = mock(RequestClient.class);
    private IntegrationClientExchange exchange;

    @BeforeEach
    void setUp() {
        ConnectorProperties connector = new ConnectorProperties();
        connector.setBaseUrl("https://erp.example");
        connector.getRetry().setMaxAttempts(MAX_ATTEMPTS);
        connector.getRetry().setWaitDurationMs(1);
        IntegrationProperties properties = new IntegrationProperties();
        properties.getConnectors().put(CONNECTOR, connector);
        RetryConfigResolver resolver = new RetryConfigResolver(properties, new RetryDefaultProperties());
        exchange = new IntegrationClientExchange(requestClient, new HttpMiddlewareChain(List.of()),
                new HandleRequestRetryExecutor(resolver), new ExchangeRetryExecutor(resolver));
    }

    private HttpRequestContext context(boolean async) {
        HttpRequestContext context = HttpRequestContext.builder()
                .connectorName(CONNECTOR)
                .baseUrl("https://erp.example")
                .path("/so")
                .method(HttpMethod.POST)
                .body(Map.of("code", "DH1"))
                .build();
        if (async) {
            context.put(HttpRequestContext.ATTRIBUTE_ASYNC_TASK_ID, UUID.randomUUID());
        }
        return context;
    }

    private void callFails(RuntimeException error) {
        when(requestClient.exchange(anyString(), anyString(), any(HttpMethod.class), any(HttpHeaders.class), anyMap(), any(),
                any(Class.class))).thenThrow(error);
    }

    private void verifyCalls(int count) {
        verify(requestClient, times(count)).exchange(anyString(), anyString(), any(HttpMethod.class), any(HttpHeaders.class),
                anyMap(), any(), any(Class.class));
    }

    @Test
    void ioErrorIsCalledMaxAttemptsTimesNotSquared() {
        // Arrange
        callFails(new ResourceAccessException("I/O error", new ConnectException("Connection refused")));

        // Act
        assertThatThrownBy(() -> exchange.exchange(context(false), Map.class)).isInstanceOf(ResourceAccessException.class);

        // Assert: 3, was 3 × 3 = 9 before 2.7.3
        verifyCalls(MAX_ATTEMPTS);
    }

    @Test
    void unauthorizedRenewsTheTokenAndCallsAgain() {
        // Arrange
        HttpRequestContext context = context(false);
        when(requestClient.exchange(anyString(), anyString(), any(HttpMethod.class), any(HttpHeaders.class), anyMap(), any(),
                any(Class.class)))
                .thenThrow(new IntegrationRequestException(401, "401 UNAUTHORIZED", "expired"))
                .thenReturn(Map.of("ok", true));

        // Act
        Object response = exchange.exchange(context, Map.class);

        // Assert
        assertThat(response).isEqualTo(Map.of("ok", true));
        assertThat(context.isForceReNewToken()).isTrue();
        verifyCalls(2);
    }

    @Test
    void syncServerErrorKeepsItsInnerRetries() {
        // Arrange
        callFails(new IntegrationRequestException(503, "503", "down"));

        // Act
        assertThatThrownBy(() -> exchange.exchange(context(false), Map.class)).isInstanceOf(IntegrationRequestException.class);

        // Assert
        verifyCalls(MAX_ATTEMPTS);
    }

    @Test
    void asyncReadTimeoutIsNotSentAgain() {
        // Arrange
        callFails(new ResourceAccessException("I/O error", new HttpTimeoutException("Request timed out")));

        // Act
        assertThatThrownBy(() -> exchange.exchange(context(true), Map.class)).isInstanceOf(ResourceAccessException.class);

        // Assert
        verifyCalls(1);
    }

    @Test
    void asyncServerErrorIsNotSentAgain() {
        // Arrange
        callFails(new IntegrationRequestException(500, "500", "already exists?"));

        // Act
        assertThatThrownBy(() -> exchange.exchange(context(true), Map.class)).isInstanceOf(IntegrationRequestException.class);

        // Assert
        verifyCalls(1);
    }

    @Test
    void asyncConnectFailureIsRetried() {
        // Arrange
        callFails(new ResourceAccessException("I/O error", new ConnectException("Connection refused")));

        // Act
        assertThatThrownBy(() -> exchange.exchange(context(true), Map.class)).isInstanceOf(ResourceAccessException.class);

        // Assert
        verifyCalls(MAX_ATTEMPTS);
    }

    @Test
    void asyncTooManyRequestsIsRetried() {
        // Arrange
        callFails(new IntegrationRequestException(429, "429", "slow down"));

        // Act
        assertThatThrownBy(() -> exchange.exchange(context(true), Map.class)).isInstanceOf(IntegrationRequestException.class);

        // Assert
        verifyCalls(MAX_ATTEMPTS);
    }
}
