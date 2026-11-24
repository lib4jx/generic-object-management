package com.msm.core.objects.logging;

import com.msm.core.exceptions.CommonErrors;
import com.msm.core.objects.exception.ObjectErrors;
import org.junit.jupiter.api.Test;

import java.util.UUID;

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
    void integrationTaskInProgressAndProcessingAreLoggedAsConflict() {
        // Arrange
        UUID recordId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        // Act
        IntegrationLogData inProgress = resolve(ObjectErrors.integrationTaskInProgress("v2order", recordId, taskId));
        IntegrationLogData processing = resolve(ObjectErrors.integrationTaskProcessing("v2order", recordId, taskId, null));

        // Assert
        assertThat(inProgress.getStatusCode()).isEqualTo(409);
        assertThat(inProgress.getErrorCode()).isEqualTo("integration.task.in_progress");
        assertThat(processing.getStatusCode()).isEqualTo(409);
        assertThat(processing.getErrorCode()).isEqualTo("integration.task.processing");
        assertThat(processing.getErrorMessage()).isEqualTo("Tiến trình gửi sang đối tác vẫn đang chạy, vui lòng tải lại sau ít phút");
    }

    @Test
    void otherLibraryErrorsStayBadRequest() {
        IntegrationLogData data = resolve(CommonErrors.invalid("code", "Invalid field path: code;"));

        assertThat(data.getStatusCode()).isEqualTo(400);
        assertThat(data.getErrorCode()).isEqualTo("INVALID_ARGUMENT");
    }
}
