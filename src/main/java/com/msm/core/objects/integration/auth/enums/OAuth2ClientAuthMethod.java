package com.msm.core.objects.integration.auth.enums;

import com.msm.core.commons.Utils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/** How the client authenticates at the token endpoint (RFC 6749 §2.3.1), key {@code client-auth-method}. */
@Getter
@RequiredArgsConstructor
public enum OAuth2ClientAuthMethod {
    /** {@code client_id} / {@code client_secret} in the request body (default, behaviour before 2.7.9). */
    CLIENT_SECRET_POST("client_secret_post"),
    /** {@code Authorization: Basic base64(id:secret)}, form body without the client credentials (e.g. Oracle IDCS). */
    CLIENT_SECRET_BASIC("client_secret_basic");

    private final String value;

    /** Blank means {@link #CLIENT_SECRET_POST}; an unknown value throws. */
    public static OAuth2ClientAuthMethod fromValue(String value) {
        if (Utils.STR.isBlank(value)) {
            return CLIENT_SECRET_POST;
        }
        return Arrays.stream(values())
                .filter(method -> method.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported client-auth-method: " + value
                        + ", expected " + CLIENT_SECRET_POST.value + " or " + CLIENT_SECRET_BASIC.value));
    }
}
