package com.msm.core.objects.integration.retry;

import com.msm.core.objects.exception.integration.IntegrationException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Locale;

/**
 * What an error of an HTTP call says about the request: answered with a status, certainly not sent, or maybe received
 * by the partner without an answer. Decides whether an async integration call may be sent again (a request that may
 * have reached the partner is not) and how its task ends.
 */
public enum TransportErrorKind {
    /** The partner answered with an error status. */
    HTTP_STATUS,
    /** The connection never opened (refused, DNS, connect timeout): the partner did not get the request. */
    NOT_SENT,
    /** I/O error once connected (read timeout, connection reset): the partner may have got the request. */
    NO_RESPONSE,
    /** Not a transport error (mapping, bug): the request may have been sent. */
    OTHER;

    private static final String CONNECT_WORD = "connect";

    public static TransportErrorKind of(Throwable error) {
        if (hasStatus(error)) {
            return HTTP_STATUS;
        }
        for (Throwable cause = error; cause != null; cause = next(cause)) {
            if (isConnectFailure(cause)) {
                return NOT_SENT;
            }
        }
        for (Throwable cause = error; cause != null; cause = next(cause)) {
            if (cause instanceof IOException) {
                return NO_RESPONSE;
            }
        }
        return OTHER;
    }

    private static boolean hasStatus(Throwable error) {
        return (error instanceof IntegrationException integration && integration.getStatusCode() != null)
                || error instanceof RestClientResponseException;
    }

    private static boolean isConnectFailure(Throwable error) {
        if (error instanceof ConnectException || error instanceof UnknownHostException || error instanceof NoRouteToHostException
                || error instanceof HttpConnectTimeoutException || error instanceof UnresolvedAddressException) {
            return true;
        }
        // java.net.Socket says "Connect timed out" for a connect timeout and "Read timed out" for a read timeout
        return error instanceof SocketTimeoutException
                && error.getMessage() != null
                && error.getMessage().toLowerCase(Locale.ROOT).contains(CONNECT_WORD);
    }

    private static Throwable next(Throwable error) {
        Throwable cause = error.getCause();
        return cause == error ? null : cause;
    }
}
