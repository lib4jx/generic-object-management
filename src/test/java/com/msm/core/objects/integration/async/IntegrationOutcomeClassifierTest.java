package com.msm.core.objects.integration.async;

import com.msm.core.objects.exception.integration.AuthenticationException;
import com.msm.core.objects.exception.integration.IntegrationRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Design §6, row by row. */
class IntegrationOutcomeClassifierTest {

    private final IntegrationOutcomeClassifier classifier = new IntegrationOutcomeClassifier();
    private final RecordingHandler handler = new RecordingHandler();

    @Test
    void acceptedAnswerSucceeds() {
        // Act
        ClassifiedOutcome outcome = classifier.classifyResponse(handler, Map.of("HeaderId", 1));

        // Assert
        assertThat(outcome.status()).isEqualTo(IntegrationTaskStatus.SUCCEEDED);
        assertThat(outcome.outcome()).isEqualTo(IntegrationOutcome.ACCEPTED);
        assertThat(outcome.response()).isEqualTo(Map.of("HeaderId", 1));
    }

    @Test
    void refusedAnswerFailsWithTheHandlersCode() {
        // Arrange
        handler.business = BusinessOutcome.rejected("ERP_FLAG_FALSE", "flag false");

        // Act
        ClassifiedOutcome outcome = classifier.classifyResponse(handler, Map.of("flag", false));

        // Assert
        assertThat(outcome.status()).isEqualTo(IntegrationTaskStatus.FAILED);
        assertThat(outcome.outcome()).isEqualTo(IntegrationOutcome.REJECTED);
        assertThat(outcome.error().code()).isEqualTo("ERP_FLAG_FALSE");
    }

    @Test
    void errorStatusRecognisedAsAlreadyDoneByTheHandler() {
        // Arrange
        handler.errorBusiness = Optional.of(BusinessOutcome.alreadyDone("SO exists"));

        // Act
        ClassifiedOutcome outcome = classifier.classifyError(handler, new IntegrationRequestException(500, "500", "{already exists}"));

        // Assert
        assertThat(handler.events).containsExactly("classifyError");
        assertThat(outcome.isAlreadyDone()).isTrue();
        assertThat(outcome.error().statusCode()).isEqualTo(500);
        assertThat(outcome.error().responseBody()).isEqualTo("{already exists}");
    }

    @Test
    void errorStatusRecognisedAsRefusalByTheHandler() {
        // Arrange
        handler.errorBusiness = Optional.of(BusinessOutcome.rejected("ERP_RULE", "rule"));

        // Act
        ClassifiedOutcome outcome = classifier.classifyError(handler, new IntegrationRequestException(500, "500", "rule"));

        // Assert
        assertThat(outcome.status()).isEqualTo(IntegrationTaskStatus.FAILED);
        assertThat(outcome.outcome()).isEqualTo(IntegrationOutcome.REJECTED);
    }

    @ParameterizedTest
    @CsvSource({
            "400, FAILED, HTTP_ERROR",
            "404, FAILED, HTTP_ERROR",
            "401, FAILED, HTTP_ERROR",
            "429, FAILED, RATE_LIMITED",
            "408, UNCONFIRMED, NO_RESPONSE",
            "500, UNCONFIRMED, NO_RESPONSE",
            "502, UNCONFIRMED, NO_RESPONSE",
            "504, UNCONFIRMED, NO_RESPONSE"
    })
    void defaultTableForStatusesTheHandlerDoesNotRecognise(int status, IntegrationTaskStatus expected, IntegrationOutcome outcome) {
        // Act
        ClassifiedOutcome result = classifier.classifyError(handler, new IntegrationRequestException(status, String.valueOf(status), "body"));

        // Assert
        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.error().statusCode()).isEqualTo(status);
        assertThat(result.error().responseBody()).isEqualTo("body");
    }

    @Test
    void noConnectionFails() {
        // Act
        ClassifiedOutcome outcome = classifier.classifyError(handler,
                new ResourceAccessException("I/O error", new ConnectException("Connection refused")));

        // Assert
        assertThat(outcome.status()).isEqualTo(IntegrationTaskStatus.FAILED);
        assertThat(outcome.outcome()).isEqualTo(IntegrationOutcome.NOT_SENT);
        assertThat(handler.events).isEmpty();
    }

    @Test
    void readTimeoutIsUnconfirmed() {
        // Act
        ClassifiedOutcome outcome = classifier.classifyError(handler,
                new ResourceAccessException("I/O error", new HttpTimeoutException("Request timed out")));

        // Assert
        assertThat(outcome.status()).isEqualTo(IntegrationTaskStatus.UNCONFIRMED);
        assertThat(outcome.outcome()).isEqualTo(IntegrationOutcome.NO_RESPONSE);
    }

    @Test
    void unknownErrorIsUnconfirmed() {
        // Act
        ClassifiedOutcome outcome = classifier.classifyError(handler, new IllegalStateException("cannot map the answer"));

        // Assert
        assertThat(outcome.status()).isEqualTo(IntegrationTaskStatus.UNCONFIRMED);
        assertThat(outcome.outcome()).isEqualTo(IntegrationOutcome.UNKNOWN_ERROR);
        assertThat(outcome.error().cause()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tokenFailureIsNotSent() {
        // Act
        ClassifiedOutcome outcome = classifier.classifyError(handler, new AuthenticationException());

        // Assert
        assertThat(outcome.status()).isEqualTo(IntegrationTaskStatus.FAILED);
        assertThat(outcome.outcome()).isEqualTo(IntegrationOutcome.HTTP_ERROR);
    }
}
