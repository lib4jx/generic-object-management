package com.msm.core.objects.integration.auth.oauth2;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.msm.core.objects.cache.RedisCacheOperator;
import com.msm.core.objects.config.IntegrationProperties;
import com.msm.core.objects.integration.RequestClient;
import com.msm.core.objects.integration.context.HttpRequestContext;
import com.msm.core.objects.integration.data.AuthProviderProperties;
import com.msm.core.objects.integration.data.ConnectorProperties;
import com.msm.core.objects.integration.data.outh2.OAuth2Token;
import com.msm.core.objects.integration.retry.RetryExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 2.7.9: client_secret_basic, cache key per client and scope, renewal skew-seconds before exp. */
class CachedOAuth2TokenProviderTest {

    private static final String CONNECTOR = "bip";
    private static final String TOKEN_URL = "https://idcs.example/oauth2/v1/token";
    private static final String CLIENT_ID = "client-1";
    private static final String CLIENT_SECRET = "s3cr+t/=";
    private static final String SCOPE = "urn:opc:resource:fa:instanceid=1urn:opc:resource:consumer::all";
    private static final long ONE_HOUR_SECONDS = 3600;
    private static final long SKEW_SECONDS = 60;

    private final RequestClient requestClient = mock(RequestClient.class);
    private final RedisCacheOperator redisCacheOperator = mock(RedisCacheOperator.class);
    private final IntegrationProperties integrationProperties = new IntegrationProperties();
    private final RetryExecutor retryExecutor = new RetryExecutor() {
        @Override
        public <T> T execute(HttpRequestContext context, Supplier<T> supplier) {
            return supplier.get();
        }
    };
    private CachedOAuth2TokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new CachedOAuth2TokenProvider(requestClient, retryExecutor, integrationProperties, redisCacheOperator);
    }

    private void connector(String name, Map<String, String> properties, Map<String, String> headers) {
        AuthProviderProperties auth = new AuthProviderProperties();
        auth.setProvider("oauth2-credentials");
        Map<String, String> raw = new HashMap<>();
        raw.put("token-url", TOKEN_URL);
        raw.put("client-id", CLIENT_ID);
        raw.put("client-secret", CLIENT_SECRET);
        raw.put("scope", SCOPE);
        raw.put("access-token-path", "access_token");
        raw.putAll(properties);
        auth.setProperties(raw);
        auth.setHeaders(headers);
        ConnectorProperties connectorProperties = new ConnectorProperties();
        connectorProperties.setBaseUrl("https://fusion.example");
        connectorProperties.setAuth(auth);
        integrationProperties.getConnectors().put(name, connectorProperties);
    }

    private HttpRequestContext context(String name) {
        return HttpRequestContext.builder().connectorName(name).build();
    }

    private static String jwt(long secondsToLive) {
        return JWT.create().withSubject(CLIENT_ID).withExpiresAt(Instant.now().plusSeconds(secondsToLive))
                .sign(Algorithm.HMAC256("test-only"));
    }

    /** A token in Redis that lived {@code lifetimeSeconds} when it was fetched. */
    private static OAuth2Token cached(String accessToken, long lifetimeSeconds) {
        OAuth2Token token = new OAuth2Token();
        token.setAccessToken(accessToken);
        token.setCreatedAt(Instant.now());
        token.setExpiresIn(lifetimeSeconds * 1000);
        return token;
    }

    private void tokenEndpointReturns(String accessToken) {
        when(requestClient.post(eq(TOKEN_URL), eq(""), any(HttpHeaders.class), any(), eq(Object.class)))
                .thenReturn(Map.of("access_token", accessToken, "token_type", "Bearer"));
    }

    private HttpHeaders sentHeaders() {
        ArgumentCaptor<HttpHeaders> headers = ArgumentCaptor.forClass(HttpHeaders.class);
        verify(requestClient).post(eq(TOKEN_URL), eq(""), headers.capture(), any(), eq(Object.class));
        return headers.getValue();
    }

    private Object sentBody() {
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(requestClient).post(eq(TOKEN_URL), eq(""), any(HttpHeaders.class), body.capture(), eq(Object.class));
        return body.getValue();
    }

    private void verifyTokenFetched(int count) {
        verify(requestClient, times(count)).post(anyString(), anyString(), any(HttpHeaders.class), any(), eq(Object.class));
    }

    @Test
    void defaultClientAuthSendsCredentialsInJsonBodyAsBefore() {
        // Arrange
        connector(CONNECTOR, Map.of(), null);
        String accessToken = jwt(ONE_HOUR_SECONDS);
        tokenEndpointReturns(accessToken);

        // Act
        String token = provider.getToken(context(CONNECTOR));

        // Assert
        assertThat(token).isEqualTo(accessToken);
        HttpHeaders headers = sentHeaders();
        assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(headers.containsKey(HttpHeaders.AUTHORIZATION)).isFalse();
        assertThat(sentBody()).isEqualTo(Map.of(
                "grant_type", "client_credentials",
                "client_id", CLIENT_ID,
                "client_secret", CLIENT_SECRET,
                "scope", SCOPE));
    }

    @Test
    void defaultClientAuthWithStrictJsonSendsJsonString() {
        // Arrange
        connector(CONNECTOR, Map.of("strict-json-mode", "true"), null);
        tokenEndpointReturns(jwt(ONE_HOUR_SECONDS));

        // Act
        provider.getToken(context(CONNECTOR));

        // Assert
        assertThat(sentBody()).isInstanceOf(String.class);
        assertThat((String) sentBody()).contains("\"client_secret\"");
    }

    @Test
    void clientSecretBasicSendsBasicHeaderAndFormBodyWithoutCredentials() {
        // Arrange
        connector(CONNECTOR, Map.of("client-auth-method", "client_secret_basic"), null);
        tokenEndpointReturns(jwt(ONE_HOUR_SECONDS));

        // Act
        provider.getToken(context(CONNECTOR));

        // Assert: id and secret form-encoded (+ / = escaped) before the Basic encoding
        HttpHeaders headers = sentHeaders();
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString((CLIENT_ID + ":s3cr%2Bt%2F%3D").getBytes(StandardCharsets.UTF_8));
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo(expected);
        assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_FORM_URLENCODED);
        assertThat(sentBody()).isEqualTo(Map.of("grant_type", "client_credentials", "scope", SCOPE));
    }

    @Test
    void clientSecretBasicKeepsConfiguredContentType() {
        // Arrange
        connector(CONNECTOR, Map.of("client-auth-method", "client_secret_basic"),
                Map.of(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded;charset=UTF-8"));
        tokenEndpointReturns(jwt(ONE_HOUR_SECONDS));

        // Act
        provider.getToken(context(CONNECTOR));

        // Assert
        HttpHeaders headers = sentHeaders();
        assertThat(headers.get(HttpHeaders.CONTENT_TYPE)).containsExactly("application/x-www-form-urlencoded;charset=UTF-8");
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).startsWith("Basic ");
    }

    @Test
    void connectorsOnTheSameTokenUrlWithAnotherClientOrScopeUseAnotherCacheKey() {
        // Arrange
        connector("bip", Map.of(), null);
        connector("bip-other-client", Map.of("client-id", "client-2"), null);
        connector("bip-other-scope", Map.of("scope", "urn:other"), null);
        connector("bip-same", Map.of(), null);
        tokenEndpointReturns(jwt(ONE_HOUR_SECONDS));

        // Act
        provider.getToken(context("bip"));
        provider.getToken(context("bip-other-client"));
        provider.getToken(context("bip-other-scope"));
        provider.getToken(context("bip-same"));

        // Assert
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(redisCacheOperator, times(4)).get(keys.capture(), eq(OAuth2Token.class));
        List<String> values = keys.getAllValues();
        assertThat(values.get(0)).startsWith("default:oauth2-credentials:https_idcs_example_oauth2_v1_token:");
        assertThat(values.get(0)).doesNotContain(CLIENT_ID);
        assertThat(values.get(1)).isNotEqualTo(values.get(0));
        assertThat(values.get(2)).isNotEqualTo(values.get(0)).isNotEqualTo(values.get(1));
        assertThat(values.get(3)).isEqualTo(values.get(0));
    }

    @Test
    void cachedTokenValidBeyondTheSkewIsReused() {
        // Arrange
        connector(CONNECTOR, Map.of(), null);
        String accessToken = jwt(SKEW_SECONDS * 2);
        when(redisCacheOperator.get(anyString(), eq(OAuth2Token.class))).thenReturn(cached(accessToken, ONE_HOUR_SECONDS));

        // Act
        String token = provider.getToken(context(CONNECTOR));

        // Assert
        assertThat(token).isEqualTo(accessToken);
        verifyTokenFetched(0);
    }

    @Test
    void cachedTokenExpiringWithinTheSkewIsRenewed() {
        // Arrange
        connector(CONNECTOR, Map.of(), null);
        when(redisCacheOperator.get(anyString(), eq(OAuth2Token.class))).thenReturn(cached(jwt(SKEW_SECONDS - 5), ONE_HOUR_SECONDS));
        String fresh = jwt(ONE_HOUR_SECONDS);
        tokenEndpointReturns(fresh);

        // Act
        String token = provider.getToken(context(CONNECTOR));

        // Assert
        assertThat(token).isEqualTo(fresh);
        verifyTokenFetched(1);
    }

    @Test
    void forceRenewFetchesEvenAValidCachedToken() {
        // Arrange
        connector(CONNECTOR, Map.of(), null);
        when(redisCacheOperator.get(anyString(), eq(OAuth2Token.class))).thenReturn(cached(jwt(ONE_HOUR_SECONDS), ONE_HOUR_SECONDS));
        tokenEndpointReturns(jwt(ONE_HOUR_SECONDS));
        HttpRequestContext ctx = context(CONNECTOR);
        ctx.setForceReNewToken(true);

        // Act
        provider.getToken(ctx);

        // Assert
        verifyTokenFetched(1);
    }

    @Test
    void newTokenIsCachedUntilItIsDueForRenewal() {
        // Arrange
        connector(CONNECTOR, Map.of(), null);
        tokenEndpointReturns(jwt(ONE_HOUR_SECONDS));

        // Act
        provider.getToken(context(CONNECTOR));

        // Assert: TTL = remaining lifetime - skew (3540 s, a little less as time passes)
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(redisCacheOperator).set(anyString(), any(OAuth2Token.class), ttl.capture());
        assertThat(ttl.getValue()).isBetween(Duration.ofSeconds(ONE_HOUR_SECONDS - SKEW_SECONDS - 5),
                Duration.ofSeconds(ONE_HOUR_SECONDS - SKEW_SECONDS));
    }

    @Test
    void shortLivedTokenIsCachedForHalfItsLife() {
        // Arrange: lives 40 s, shorter than the 60 s skew
        connector(CONNECTOR, Map.of(), null);
        tokenEndpointReturns(jwt(40));

        // Act
        provider.getToken(context(CONNECTOR));

        // Assert: skew capped at half the lifetime (20 s), TTL about 20 s
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(redisCacheOperator).set(anyString(), any(OAuth2Token.class), ttl.capture());
        assertThat(ttl.getValue()).isBetween(Duration.ofSeconds(15), Duration.ofSeconds(20));
    }

    @Test
    void shortLivedCachedTokenIsReusedInTheFirstHalfOfItsLife() {
        // Arrange: lived 40 s when fetched, 30 s left (skew capped at 20 s)
        connector(CONNECTOR, Map.of(), null);
        String accessToken = jwt(30);
        when(redisCacheOperator.get(anyString(), eq(OAuth2Token.class))).thenReturn(cached(accessToken, 40));

        // Act
        String token = provider.getToken(context(CONNECTOR));

        // Assert
        assertThat(token).isEqualTo(accessToken);
        verifyTokenFetched(0);
    }

    @Test
    void tokenWithoutExpIsCachedWithoutTtlAndReusedAsBefore() {
        // Arrange
        connector(CONNECTOR, Map.of(), null);
        String noExp = JWT.create().withSubject(CLIENT_ID).sign(Algorithm.HMAC256("test-only"));
        tokenEndpointReturns(noExp);

        // Act
        provider.getToken(context(CONNECTOR));
        when(redisCacheOperator.get(anyString(), eq(OAuth2Token.class))).thenReturn(cached(noExp, 0));
        provider.getToken(context(CONNECTOR));

        // Assert
        verify(redisCacheOperator).set(anyString(), any(OAuth2Token.class));
        verifyTokenFetched(1);
    }
}
