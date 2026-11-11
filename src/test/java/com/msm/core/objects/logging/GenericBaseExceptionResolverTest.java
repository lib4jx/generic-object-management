package com.msm.core.objects.logging;

import com.msm.core.exceptions.CommonErrors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GenericBaseExceptionResolverTest {

    private final GenericBaseExceptionResolver resolver = new GenericBaseExceptionResolver();

    private IntegrationLogData resolve(RuntimeException exception) {
        IntegrationLogData.IntegrationLogDataBuilder builder = IntegrationLogData.builder();
        resolver.resolve(exception, builder);
        return builder.build();
    }

    @Test
    void relationAccessDeniedIsLoggedAsForbidden() {
        IntegrationLogData data = resolve(CommonErrors.relationAccessDenied("v2blanketorder", "No permission to view v2blanketorder"));

        assertThat(data.getStatusCode()).isEqualTo(403);
        assertThat(data.getErrorCode()).isEqualTo("RELATION_ACCESS_DENIED");
        assertThat(data.getErrorMessage()).isEqualTo("No permission to view v2blanketorder");
    }

    @Test
    void otherLibraryErrorsStayBadRequest() {
        IntegrationLogData data = resolve(CommonErrors.invalid("code", "Invalid field path: code;"));

        assertThat(data.getStatusCode()).isEqualTo(400);
        assertThat(data.getErrorCode()).isEqualTo("INVALID_ARGUMENT");
    }
}
