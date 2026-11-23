package com.msm.core.objects.integration.factory;

import com.msm.core.objects.integration.data.ConnectorProperties;
import com.msm.core.objects.integration.data.TimeoutProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Builds the HTTP client of a connector with a {@code timeout} block: the JDK {@link HttpClient} (no extra library),
 * connect timeout on the client, read timeout on the request factory. Message converters and customizers come from the
 * service's {@link RestClient.Builder}, as for the shared {@code integrationRestClient}.
 */
public class RestClientFactory {

    private final RestClient.Builder prototype;

    public RestClientFactory(RestClient.Builder prototype) {
        this.prototype = prototype;
    }

    public RestClient create(ConnectorProperties props) {
        TimeoutProperties timeout = props.getTimeout() == null ? new TimeoutProperties() : props.getTimeout();
        // redirects as Spring Boot's default JDK client of the shared integrationRestClient
        HttpClient.Builder httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL);
        if (timeout.getConnectMs() != null) {
            httpClient.connectTimeout(Duration.ofMillis(timeout.getConnectMs()));
        }
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient.build());
        if (timeout.getReadMs() != null) {
            requestFactory.setReadTimeout(Duration.ofMillis(timeout.getReadMs()));
        }
        return prototype.clone().requestFactory(requestFactory).build();
    }
}
