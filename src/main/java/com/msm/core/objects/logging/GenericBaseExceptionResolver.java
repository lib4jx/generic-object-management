package com.msm.core.objects.logging;

import com.msm.core.exceptions.GenericBaseException;
import com.msm.core.exceptions.RelationAccessDeniedException;
import com.msm.core.objects.exception.IntegrationTaskInProgressException;
import com.msm.core.objects.exception.IntegrationTaskProcessingException;
import org.springframework.http.HttpStatus;

/**
 * Default {@link IntegrationErrorResolver} for the lib-owned {@link GenericBaseException} family.
 * Runs last (lowest precedence) so service-specific resolvers can take priority. This is the only
 * exception mapping the reusable logging package can own, since {@code GenericBaseException} is a
 * library type.
 *
 * <p>Logged as 400, except {@link RelationAccessDeniedException} (a filter reached, through a relation, an object
 * the caller may not view): 403, and {@link IntegrationTaskInProgressException} /
 * {@link IntegrationTaskProcessingException}: 409, as the services answer them.
 */

public class GenericBaseExceptionResolver implements IntegrationErrorResolver {

    @Override
    public boolean supports(Throwable ex) {
        return ex instanceof GenericBaseException;
    }

    @Override
    public void resolve(Throwable ex, IntegrationLogData.IntegrationLogDataBuilder builder) {
        GenericBaseException exception = (GenericBaseException) ex;
        builder.statusCode(statusOf(exception).value())
                .errorCode(exception.getCode() != null ? exception.getCode().getCode() : null)
                .errorMessage(exception.getMessage());
    }

    private HttpStatus statusOf(GenericBaseException exception) {
        if (exception instanceof RelationAccessDeniedException) {
            return HttpStatus.FORBIDDEN;
        }
        if (exception instanceof IntegrationTaskInProgressException || exception instanceof IntegrationTaskProcessingException) {
            return HttpStatus.CONFLICT;
        }
        return HttpStatus.BAD_REQUEST;
    }
}
