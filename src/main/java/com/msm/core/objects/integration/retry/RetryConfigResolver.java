package com.msm.core.objects.integration.retry;

import com.msm.core.objects.config.IntegrationProperties;
import com.msm.core.objects.exception.integration.IntegrationException;
import com.msm.core.objects.integration.context.HttpRequestContext;
import com.msm.core.objects.integration.data.ConnectorProperties;
import com.msm.core.objects.integration.data.retry.RetryDefaultProperties;
import com.msm.core.objects.integration.data.retry.RetryProperties;
import com.msm.core.objects.integration.data.retry.RetryRequestConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

@RequiredArgsConstructor
public class RetryConfigResolver {

    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private final IntegrationProperties properties;

    private final RetryDefaultProperties defaults;


    private RetryConfig retryConfig(HttpRequestContext context, Predicate<Throwable> predicateRetry, List<Class<? extends Throwable>> ignoreExceptions) {
        ConnectorProperties connector = properties.getConnectors().get(context.getConnectorName());
        RetryProperties retryProperties = connector.getRetry();
        RetryRequestConfig override = context.getRetryConfig();
        Class<? extends Throwable>[] ignoreExceptionsArray = ignoreExceptions.toArray(Class[]::new);


        int maxAttempts = override != null && override.getMaxAttempts() != null
                ? override.getMaxAttempts()
                : retryProperties.getMaxAttempts();

        Duration waitDuration = override != null && override.getWaitDuration() != null
                ? override.getWaitDuration()
                : Duration.ofMillis(retryProperties.getWaitDurationMs());

        return RetryConfig
                .custom()
                .maxAttempts(maxAttempts)
                .waitDuration(waitDuration)
                .ignoreExceptions(ignoreExceptionsArray)
                .retryOnException(predicateRetry)
                .build();
    }

    public Retry resolve(HttpRequestContext context) {
        Predicate<Throwable> predicate = context.isAsyncTask() ? shouldRetryAsyncPredicate() : shouldRetryPredicate();
        RetryConfig config = retryConfig(context, predicate, List.of(IllegalArgumentException.class, NullPointerException.class));
        return Retry.of(context.getConnectorName() + "-retry", config);
    }

    /**
     * Call of an async integration task: sent again only when the partner certainly did not get it (no connection,
     * 429). A read timeout, a connection reset, 408 or 5xx may have reached the partner: no retry, the task becomes
     * UNCONFIRMED. 401 is renewed by the exchange retry.
     */
    private Predicate<Throwable> shouldRetryAsyncPredicate() {
        return throwable -> {
            if (throwable instanceof IntegrationException ex && ex.getStatusCode() != null) {
                return ex.getStatusCode() == HTTP_TOO_MANY_REQUESTS;
            }
            if (throwable instanceof HttpClientErrorException ex) {
                return ex.getStatusCode().value() == HTTP_TOO_MANY_REQUESTS;
            }
            return TransportErrorKind.of(throwable) == TransportErrorKind.NOT_SENT;
        };
    }

    private Predicate<Throwable> shouldRetryPredicate() {
        return throwable -> {
            if (throwable instanceof IntegrationException ex) {
                return checkStatusCode(ex.getStatusCode());
            }
            if (throwable instanceof HttpClientErrorException ex) {
                return checkStatusCode(ex.getStatusCode().value());
            }
            return true;
        };
    }

    private boolean checkStatusCode(int statusCode) {
        if (statusCode == HTTP_TOO_MANY_REQUESTS) {
            return true;
        }
        return statusCode < 400 || statusCode > 500;
    }




    public Retry resolveExchangeRetry(HttpRequestContext context) {
        RetryConfig config = retryConfig(context, shouldExchangeRetryPredicate(), List.of(IllegalArgumentException.class, NullPointerException.class));
        return Retry.of(context.getConnectorName() + "-exchangeRetry", config);
    }


    /**
     * The exchange retry only renews the token: 401, then the next attempt asks for a new one. Every other error was
     * already retried by {@link #resolve}; retrying it here too made max-attempts × max-attempts calls.
     */
    private Predicate<Throwable> shouldExchangeRetryPredicate() {
        return throwable -> {
            if (throwable instanceof IntegrationException ex) {
                return checkStatusCodeExchangeRetry(ex.getStatusCode());
            }
            if (throwable instanceof HttpClientErrorException ex) {
                return checkStatusCodeExchangeRetry(ex.getStatusCode().value());
            }
            return false;
        };
    }

    private boolean checkStatusCodeExchangeRetry(Integer statusCode) {
        return statusCode != null && statusCode == HTTP_UNAUTHORIZED;
    }
}
