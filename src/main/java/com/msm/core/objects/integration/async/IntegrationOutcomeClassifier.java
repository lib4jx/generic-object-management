package com.msm.core.objects.integration.async;

import com.msm.core.objects.exception.integration.IntegrationException;
import com.msm.core.objects.integration.retry.TransportErrorKind;
import org.springframework.web.client.RestClientResponseException;

import java.util.Optional;

/**
 * Turns the answer or the error of a call into a task status (design §6). A 2xx goes to the handler's
 * {@code classify}; an error status first to its {@code classifyError}, then to the default table:
 * <ul>
 *   <li>408, 5xx, read timeout, connection reset, unknown error: UNCONFIRMED (the partner may have done it);</li>
 *   <li>other 4xx (401 included, once the token was renewed), 429 after the retries, no connection: FAILED.</li>
 * </ul>
 */
public class IntegrationOutcomeClassifier {

    private static final int HTTP_CLIENT_ERROR_MIN = 400;
    private static final int HTTP_REQUEST_TIMEOUT = 408;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_SERVER_ERROR_MIN = 500;

    public ClassifiedOutcome classifyResponse(AsyncIntegrationHandler<?, ?> handler, Object response) {
        return fromBusiness(handler.classify(response), response, null, null);
    }

    public ClassifiedOutcome classifyError(AsyncIntegrationHandler<?, ?> handler, RuntimeException error) {
        Optional<HttpError> httpError = httpError(error);
        if (httpError.isPresent()) {
            HttpError http = httpError.get();
            return handler.classifyError(http.status(), http.body())
                    .map(business -> fromBusiness(business, null, http, error))
                    .orElseGet(() -> classifyStatus(http, error));
        }
        return switch (TransportErrorKind.of(error)) {
            case NOT_SENT -> ClassifiedOutcome.failed(IntegrationOutcome.NOT_SENT,
                    IntegrationError.of(IntegrationOutcome.NOT_SENT, message(error), error));
            case NO_RESPONSE, HTTP_STATUS -> ClassifiedOutcome.unconfirmed(IntegrationOutcome.NO_RESPONSE,
                    IntegrationError.of(IntegrationOutcome.NO_RESPONSE, message(error), error));
            case OTHER -> ClassifiedOutcome.unconfirmed(IntegrationOutcome.UNKNOWN_ERROR,
                    IntegrationError.of(IntegrationOutcome.UNKNOWN_ERROR, message(error), error));
        };
    }

    private ClassifiedOutcome classifyStatus(HttpError http, RuntimeException error) {
        int status = http.status();
        if (status == HTTP_REQUEST_TIMEOUT || status >= HTTP_SERVER_ERROR_MIN || status < HTTP_CLIENT_ERROR_MIN) {
            return ClassifiedOutcome.unconfirmed(IntegrationOutcome.NO_RESPONSE, httpIntegrationError(IntegrationOutcome.NO_RESPONSE, http, error));
        }
        IntegrationOutcome outcome = status == HTTP_TOO_MANY_REQUESTS ? IntegrationOutcome.RATE_LIMITED : IntegrationOutcome.HTTP_ERROR;
        return ClassifiedOutcome.failed(outcome, httpIntegrationError(outcome, http, error));
    }

    private ClassifiedOutcome fromBusiness(BusinessOutcome business, Object response, HttpError http, RuntimeException error) {
        Integer status = http == null ? null : http.status();
        String body = http == null ? null : http.body();
        return switch (business.kind()) {
            case ACCEPTED -> ClassifiedOutcome.succeeded(IntegrationOutcome.ACCEPTED, response);
            case REJECTED -> ClassifiedOutcome.failed(IntegrationOutcome.REJECTED, new IntegrationError(
                    codeOr(business.code(), IntegrationOutcome.REJECTED), business.message(), status, body, error));
            case ALREADY_DONE -> ClassifiedOutcome.unconfirmed(IntegrationOutcome.ALREADY_DONE, new IntegrationError(
                    codeOr(business.code(), IntegrationOutcome.ALREADY_DONE), business.message(), status, body, error));
        };
    }

    private IntegrationError httpIntegrationError(IntegrationOutcome outcome, HttpError http, RuntimeException error) {
        return new IntegrationError(outcome.name(), message(error), http.status(), http.body(), error);
    }

    private Optional<HttpError> httpError(RuntimeException error) {
        if (error instanceof IntegrationException integration && integration.getStatusCode() != null) {
            // DefaultRequestClient puts the body of the error answer in the message
            return Optional.of(new HttpError(integration.getStatusCode(), integration.getMessage()));
        }
        if (error instanceof RestClientResponseException response) {
            return Optional.of(new HttpError(response.getStatusCode().value(), response.getResponseBodyAsString()));
        }
        return Optional.empty();
    }

    private String codeOr(String code, IntegrationOutcome fallback) {
        return code == null || code.isBlank() ? fallback.name() : code;
    }

    private String message(Throwable error) {
        return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
    }

    private record HttpError(int status, String body) {
    }
}
