package com.msm.core.objects.integration;

import com.msm.core.objects.entity.enums.IntegrationAction;
import com.msm.core.objects.entity.enums.IntegrationStatus;
import com.msm.core.objects.integration.context.ExecutionEvent;
import com.msm.core.objects.integration.context.HttpRequestContext;
import com.msm.core.objects.integration.middleware.HttpMiddlewareChain;
import com.msm.core.objects.integration.retry.RetryExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;

import java.time.Instant;

@Slf4j
public class IntegrationClientExchange {
    private final ConnectorRequestClientRegistry requestClients;
    private final HttpMiddlewareChain chain;
    private final RetryExecutor retryExecutor;
    private final RetryExecutor exchangeRetryExecutor;

    /** Every connector on {@code requestClient}, as before 2.8.0. */
    public IntegrationClientExchange(RequestClient requestClient, HttpMiddlewareChain chain, RetryExecutor retryExecutor,
                                     RetryExecutor exchangeRetryExecutor) {
        this(new ConnectorRequestClientRegistry(requestClient), chain, retryExecutor, exchangeRetryExecutor);
    }

    /** The client of each call is the one of its connector ({@code timeout} block), else the shared one. */
    public IntegrationClientExchange(ConnectorRequestClientRegistry requestClients, HttpMiddlewareChain chain,
                                     RetryExecutor retryExecutor, RetryExecutor exchangeRetryExecutor) {
        this.requestClients = requestClients;
        this.chain = chain;
        this.retryExecutor = retryExecutor;
        this.exchangeRetryExecutor = exchangeRetryExecutor;
    }

    public <T> T exchange(HttpRequestContext context, Class<T> responseType) {

//        try {
//            chain.before(context);
//            T response = handleRequest(context, responseType);
//            chain.after(context, response);
//            return response;
//        } catch (Exception ex) {
//            chain.error(context, ex);
//            throw ex;
//        }
//        return exchangeWithRetry(context, responseType);
        try {
            return exchangeRetryExecutor.execute(context, () ->  exchangeWithRetry(context, responseType));
        } catch (Exception ex) {
            chain.error(context, ex);
            throw ex;
        }
    }

    public <T> T exchange(HttpRequestContext context, ParameterizedTypeReference<T> responseType) {

//        try {
//
//            chain.before(context);
//
//            T response = handleRequest(context, responseType);
//
//            chain.after(context, response);
//
//            return response;
//
//        } catch (Exception ex) {
//            chain.error(context, ex);
//            throw ex;
//        }

//        return exchangeWithRetry(context, responseType);
        try {
            return exchangeRetryExecutor.execute(context, () ->  exchangeWithRetry(context, responseType));
        } catch (Exception ex) {
            chain.error(context, ex);
            throw ex;
        }
    }



    public <T> T exchangeWithRetry(HttpRequestContext context, ParameterizedTypeReference<T> responseType) {
        try {
            chain.before(context);
            T response = handleRequest(context, responseType);
            chain.after(context, response);
            return response;
        } catch (Exception ex) {
            context.addEvent(ExecutionEvent.builder()
                    .timestamp(Instant.now())
                    .component(getClass().getSimpleName())
                    .componentExecution(getClass().getSimpleName() + ".exchangeWithRetry")
                    .action(IntegrationAction.EXCHANGE_REQUEST.name())
                    .status(IntegrationStatus.FAILED.name())
                    .message(ex.getMessage())
                    .errorType(ex.getClass().getSimpleName())
                    .build()
            );
            throw ex;
        }
    }

    public <T> T exchangeWithRetry(HttpRequestContext context, Class<T> responseType) {
        try {
            chain.before(context);
            T response = handleRequest(context, responseType);
            chain.after(context, response);
            return response;
        } catch (Exception ex) {
            context.addEvent(ExecutionEvent.builder()
                    .timestamp(Instant.now())
                    .component(getClass().getSimpleName())
                    .componentExecution(getClass().getSimpleName() + ".exchangeWithRetry")
                    .action(IntegrationAction.EXCHANGE_REQUEST.name())
                    .status(IntegrationStatus.FAILED.name())
                    .message(ex.getMessage())
                    .errorType(ex.getClass().getSimpleName())
                    .build()
            );
            throw ex;
        }
    }

    private <T> T handleRequest(HttpRequestContext context, Class<T> responseType) {
        context.setComponentExecution(getClass().getSimpleName()+ ".handleRequest");
        return retryExecutor.execute(context, () -> execute(context, responseType));
    }

    private <T> T handleRequest(HttpRequestContext context, ParameterizedTypeReference<T> responseType) {
        context.setComponentExecution(getClass().getSimpleName()+ ".handleRequest");
        return retryExecutor.execute(context, () -> execute(context, responseType));
    }

    private <T> T execute(HttpRequestContext context, Class<T> responseType) {

        return requestClients.get(context.getConnectorName()).exchange(
                context.getBaseUrl(),
                context.getPath(),
                context.getMethod(),
                context.getHeaders(),
                context.getQueryParams(),
                context.getBody(),
                responseType
        );
    }

    private <T> T execute(HttpRequestContext context, ParameterizedTypeReference<T> responseType) {

        return requestClients.get(context.getConnectorName()).exchange(
                context.getBaseUrl(),
                context.getPath(),
                context.getMethod(),
                context.getHeaders(),
                context.getQueryParams(),
                context.getBody(),
                responseType
        );
    }
}
