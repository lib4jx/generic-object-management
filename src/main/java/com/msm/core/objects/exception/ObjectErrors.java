package com.msm.core.objects.exception;

import com.msm.core.exceptions.CommonErrors;
import com.msm.core.exceptions.ObjectNotFoundException;

import java.util.List;
import java.util.UUID;

public class ObjectErrors {
    public static ObjectValidateException validateException(String objectName, List<ObjectErrorDetail> errors) {
        return new ObjectValidateException(objectName, errors);
    }

    public static ObjectValidateException validateException(String objectName, List<ObjectErrorDetail> errors, Throwable cause) {
        return new ObjectValidateException(objectName, errors, cause);
    }

    public static ObjectNotFoundException notFound(String name) {
        return CommonErrors.objectNotFound(name);
    }

    public static PayloadInvalidException payloadInvalidException(String name, String msg, Throwable cause) {
        return new PayloadInvalidException(name, msg, cause);
    }

    public static CreateInstanceException createInstanceException(String msg, Throwable cause) {
        return new CreateInstanceException(msg, cause);
    }

    public static IntegrationTaskInProgressException integrationTaskInProgress(String objectName, UUID recordId, UUID taskId) {
        return new IntegrationTaskInProgressException(objectName, recordId, taskId, null);
    }

    public static IntegrationTaskInProgressException integrationTaskInProgress(String objectName, UUID recordId, Throwable cause) {
        return new IntegrationTaskInProgressException(objectName, recordId, null, cause);
    }

    public static IntegrationTaskProcessingException integrationTaskProcessing(String objectName, UUID recordId, UUID taskId,
                                                                               Throwable cause) {
        return new IntegrationTaskProcessingException(objectName, recordId, taskId, cause);
    }

    public static IntegrationTaskStateException integrationTaskInvalidStatus(UUID taskId, String status) {
        return new IntegrationTaskStateException(ObjectErrorCode.INTEGRATION_TASK_INVALID_STATUS, taskId, status,
                "The task is " + status + ", the action needs UNCONFIRMED");
    }
}
