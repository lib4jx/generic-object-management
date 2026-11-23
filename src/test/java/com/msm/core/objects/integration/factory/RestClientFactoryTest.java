package com.msm.core.objects.integration.factory;

import com.msm.core.objects.integration.data.ConnectorProperties;
import com.msm.core.objects.integration.data.TimeoutProperties;
import com.msm.core.objects.integration.retry.TransportErrorKind;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** The timeouts reach a real JDK HttpClient, and its errors classify as the retry and the task status expect. */
class RestClientFactoryTest {

    private static final long READ_TIMEOUT_MS = 300;
    private static final long SLOW_ANSWER_MS = 3_000;

    private HttpServer server;
    private final RestClientFactory factory = new RestClientFactory(RestClient.builder());

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/fast", exchange -> answer(exchange, "{\"ok\":true}"));
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(SLOW_ANSWER_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            answer(exchange, "{}");
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void answer(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private ConnectorProperties connector(Long connectMs, Long readMs) {
        TimeoutProperties timeout = new TimeoutProperties();
        timeout.setConnectMs(connectMs);
        timeout.setReadMs(readMs);
        ConnectorProperties connector = new ConnectorProperties();
        connector.setTimeout(timeout);
        return connector;
    }

    private String url(String path) {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + path;
    }

    @Test
    void answerWithinTheTimeoutIsRead() {
        // Arrange
        RestClient client = factory.create(connector(1_000L, READ_TIMEOUT_MS * 10));

        // Act
        String body = client.get().uri(url("/fast")).retrieve().body(String.class);

        // Assert
        assertThat(body).isEqualTo("{\"ok\":true}");
    }

    @Test
    void slowAnswerIsAReadTimeoutClassifiedNoResponse() {
        // Arrange
        RestClient client = factory.create(connector(1_000L, READ_TIMEOUT_MS));

        // Act
        ResourceAccessException error = catchThrowableOfType(ResourceAccessException.class,
                () -> client.get().uri(url("/slow")).retrieve().body(String.class));

        // Assert
        assertThat(error.getCause()).isInstanceOf(HttpTimeoutException.class).isNotInstanceOf(HttpConnectTimeoutException.class);
        assertThat(TransportErrorKind.of(error)).isEqualTo(TransportErrorKind.NO_RESPONSE);
    }

    @Test
    void closedPortIsNotSent() throws IOException {
        // Arrange
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        RestClient client = factory.create(connector(1_000L, READ_TIMEOUT_MS));

        // Act / Assert
        assertThatThrownBy(() -> client.get().uri("http://127.0.0.1:" + closedPort + "/x").retrieve().body(String.class))
                .isInstanceOfSatisfying(ResourceAccessException.class,
                        error -> assertThat(TransportErrorKind.of(error)).isEqualTo(TransportErrorKind.NOT_SENT));
    }
}
