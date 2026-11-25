package com.msm.core.objects.integration.auth.oauth2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.msm.core.commons.Utils;
import com.msm.core.objects.cache.RedisCacheOperator;
import com.msm.core.objects.config.IntegrationProperties;
import com.msm.core.objects.integration.RequestClient;
import com.msm.core.objects.integration.auth.RedisKeyGeneratorOptimized;
import com.msm.core.objects.integration.auth.common.TokenProvider;
import com.msm.core.objects.integration.auth.enums.OAuth2ClientAuthMethod;
import com.msm.core.objects.integration.context.HttpRequestContext;
import com.msm.core.objects.integration.data.ConnectorProperties;
import com.msm.core.objects.integration.data.outh2.OAuth2Properties;
import com.msm.core.objects.integration.data.outh2.OAuth2Token;
import com.msm.core.objects.integration.data.retry.RetryRequestConfig;
import com.msm.core.objects.integration.retry.RetryExecutor;
import com.msm.core.objects.utils.JsonPathUtil;
import com.msm.core.objects.utils.JwtUtils;
import com.msm.core.security.RequestContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class CachedOAuth2TokenProvider implements TokenProvider {
    private static final long MILLIS_PER_SECOND = 1000L;
    /** Hex characters of the SHA-256 of client id and scope kept in the cache key. */
    private static final int CLIENT_KEY_LENGTH = 16;

    private final RequestClient requestClient;
    private final RetryExecutor retryExecutor;
    private final IntegrationProperties integrationProperties;
    private final RedisCacheOperator redisCacheOperator;

    @Override
    public String supportProvider() {
        return "oauth2-credentials";
    }

    @Override
    public String getToken(HttpRequestContext ctx) {
        OAuth2Properties oAuth2Context = getOAuth2Properties(ctx);
        String keyCache = cacheKey(oAuth2Context);
        OAuth2Token auth2CacheToken = redisCacheOperator.get(keyCache, OAuth2Token.class);
        if (auth2CacheToken == null
                || isDueForRenewal(auth2CacheToken, oAuth2Context.getSkewSeconds())
                || ctx.isForceReNewToken()) {
            auth2CacheToken = retryFetchToken(ctx, oAuth2Context);
            cacheToken(keyCache, auth2CacheToken, oAuth2Context.getSkewSeconds());
        }

        return auth2CacheToken.getAccessToken();
    }

    /**
     * Renewed a skew before {@code exp}, so a token does not expire on its way to the partner. A token without
     * {@code exp} is never due here (as before 2.7.9): only a 401 renews it.
     */
    private boolean isDueForRenewal(OAuth2Token token, long skewSeconds) {
        return hasExpiry(token.getAccessToken())
                && JwtUtils.getRemainingTimeMs(token.getAccessToken()) <= effectiveSkewMs(token, skewSeconds);
    }

    /** Kept in Redis until it is due for renewal; a token without {@code exp} without TTL, as before 2.7.9. */
    private void cacheToken(String keyCache, OAuth2Token token, long skewSeconds) {
        if (!hasExpiry(token.getAccessToken())) {
            redisCacheOperator.set(keyCache, token);
            return;
        }
        final long ttlMs = JwtUtils.getRemainingTimeMs(token.getAccessToken()) - effectiveSkewMs(token, skewSeconds);
        if (ttlMs > 0) {
            redisCacheOperator.set(keyCache, token, Duration.ofMillis(ttlMs));
        }
    }

    /**
     * {@code skew-seconds}, at most half of the token's lifetime ({@code expiresIn}, the time left when it was
     * fetched): a short-lived token is still cached and used for the first half of its life instead of being fetched
     * again at every call.
     */
    private long effectiveSkewMs(OAuth2Token token, long skewSeconds) {
        return Math.max(0, Math.min(skewSeconds * MILLIS_PER_SECOND, token.getExpiresIn() / 2));
    }

    private boolean hasExpiry(String accessToken) {
        return JwtUtils.getExpirationDate(accessToken) != null;
    }

    private OAuth2Token retryFetchToken(HttpRequestContext ctx, OAuth2Properties oAuth2Context) {
        HttpRequestContext authRequestContext = createAuthContext(ctx.getConnectorName(), oAuth2Context);
        try {
            return retryExecutor.execute(authRequestContext, () -> fetchToken(oAuth2Context));
        } catch (Exception ex) {
            ctx.addEvents(authRequestContext.getEvents());
            throw ex;
        }
    }

    private OAuth2Token fetchToken(OAuth2Properties oAuth2Context) {
        final boolean basicAuth = OAuth2ClientAuthMethod.fromValue(oAuth2Context.getClientAuthMethod())
                == OAuth2ClientAuthMethod.CLIENT_SECRET_BASIC;
        HttpHeaders headers = new HttpHeaders();
        Utils.CL.emptyIfNull(oAuth2Context.getHeaders()).forEach(headers::add);
        if (basicAuth) {
            // RFC 6749 §2.3.1: id and secret are form-encoded before the Basic encoding; the body must be a form
            headers.setBasicAuth(formEncode(oAuth2Context.getClientId()), formEncode(oAuth2Context.getClientSecret()),
                    StandardCharsets.UTF_8);
            if (headers.getContentType() == null) {
                headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            }
        } else if (Utils.CL.isEmpty(oAuth2Context.getHeaders())) {
            headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        }
        Map<String, String> formBody = buildBody(oAuth2Context, basicAuth);
        Object response = requestClient.post(
                oAuth2Context.getTokenUrl(),
                "",
                headers,
                !basicAuth && oAuth2Context.isStrictJsonMode() ? toJson(formBody) : formBody,
                Object.class);

        String accessToken = parseToken(response, oAuth2Context);
        OAuth2Token token = new OAuth2Token();
        token.setAccessToken(accessToken);
        token.setCreatedAt(Instant.now());
        token.setExpiresIn(JwtUtils.getRemainingTimeMs(accessToken));

        return token;
    }

    private String formEncode(String value) {
        return URLEncoder.encode(Utils.STR.defaultIfBlank(value, () -> ""), StandardCharsets.UTF_8);
    }

    private String parseToken(Object response, OAuth2Properties oAuth2Context) {
        Map<String, Object> objectMap = Utils.O.toMap(response);
        String accessTokenName = Utils.STR.defaultIfBlank(oAuth2Context.getAccessTokenPath(), () -> "token");
        return JsonPathUtil.extractValue(objectMap, accessTokenName);
    }

    /** With {@code client_secret_basic} the client credentials go in the header only, never also in the body. */
    private Map<String, String> buildBody(OAuth2Properties properties, boolean basicAuth) {
        Map<String, String> formBody = new HashMap<>();
        formBody.put("grant_type", properties.getGrantType());
        if (!basicAuth) {
            formBody.put("client_id", properties.getClientId());
            formBody.put("client_secret", properties.getClientSecret());
        }
        if(Utils.STR.isNotBlank(properties.getScope())) {
            formBody.put("scope", properties.getScope());
        }

        return formBody;
    }

    private String toJson(Map<String, String> formBody) {
        try {
            return Utils.O.toJsonString(formBody);
        } catch (JsonProcessingException e) {
            log.error(e.getMessage(), e);
            return null;
        }
    }

    private HttpRequestContext createAuthContext(String connectorName, OAuth2Properties properties) {
        RetryRequestConfig config = new RetryRequestConfig();
        config.setMaxAttempts(properties.getMaxAttempts());
        config.setWaitDuration(Duration.ofMillis(properties.getWaitDurationMs()));
        return HttpRequestContext.builder()
                .connectorName(connectorName)
                .componentExecution(getClass().getSimpleName() + ".authenticate")
                .retryConfig(config)
                .build();
    }

    private OAuth2Properties getOAuth2Properties(HttpRequestContext ctx) {
        ConnectorProperties connectorProperties = integrationProperties.getConnectors().get(ctx.getConnectorName());
        OAuth2Properties oAuth2Properties = Utils.O.convertObject(connectorProperties.getAuth().getProperties(), OAuth2Properties.class);
        oAuth2Properties.setHeaders(connectorProperties.getAuth().getHeaders());
        return oAuth2Properties;
    }

    /**
     * One token per token URL, client and scope: two connectors on the same identity server with another client or
     * scope do not take each other's token. The client id is hashed, not written to Redis as is.
     */
    private String cacheKey(OAuth2Properties oAuth2Context) {
        return RequestContextHolder.getRequestContext().getTenantCode() + ":" + supportProvider() + ":"
                + RedisKeyGeneratorOptimized.generateUrlKey(oAuth2Context.getTokenUrl()) + ":"
                + clientKey(oAuth2Context.getClientId(), oAuth2Context.getScope());
    }

    private String clientKey(String clientId, String scope) {
        final String source = Utils.STR.defaultIfBlank(clientId, () -> "") + "|" + Utils.STR.defaultIfBlank(scope, () -> "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, CLIENT_KEY_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // every JDK ships SHA-256
            throw new IllegalStateException(e);
        }
    }
}
