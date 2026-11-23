package com.msm.core.objects.integration.retry;

import com.msm.core.objects.exception.integration.IntegrationRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class TransportErrorKindTest {

    private ResourceAccessException io(IOException cause) {
        return new ResourceAccessException("I/O error on POST request: " + cause.getMessage(), cause);
    }

    @Test
    void connectionNeverOpenedIsNotSent() {
        assertThat(TransportErrorKind.of(io(new ConnectException("Connection refused")))).isEqualTo(TransportErrorKind.NOT_SENT);
        assertThat(TransportErrorKind.of(io(new UnknownHostException("erp.example")))).isEqualTo(TransportErrorKind.NOT_SENT);
        assertThat(TransportErrorKind.of(io(new HttpConnectTimeoutException("HTTP connect timed out")))).isEqualTo(TransportErrorKind.NOT_SENT);
        assertThat(TransportErrorKind.of(io(new SocketTimeoutException("Connect timed out")))).isEqualTo(TransportErrorKind.NOT_SENT);
    }

    @Test
    void errorAfterConnectingIsNoResponse() {
        assertThat(TransportErrorKind.of(io(new HttpTimeoutException("Request timed out")))).isEqualTo(TransportErrorKind.NO_RESPONSE);
        assertThat(TransportErrorKind.of(io(new SocketTimeoutException("Read timed out")))).isEqualTo(TransportErrorKind.NO_RESPONSE);
        assertThat(TransportErrorKind.of(io(new IOException("Connection reset")))).isEqualTo(TransportErrorKind.NO_RESPONSE);
    }

    @Test
    void statusAndOtherErrors() {
        assertThat(TransportErrorKind.of(new IntegrationRequestException(503, "503", "down"))).isEqualTo(TransportErrorKind.HTTP_STATUS);
        assertThat(TransportErrorKind.of(new IllegalStateException("mapping"))).isEqualTo(TransportErrorKind.OTHER);
    }
}
