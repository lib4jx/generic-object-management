package com.msm.core.objects.integration.async;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Marks when a request reached the service ({@link System#nanoTime()} in a request attribute), so an inline wait can
 * keep within {@code async.request-budget-ms} (since 2.7.8). Registered first in the chain by the auto-configuration.
 */
public class RequestStartFilter extends OncePerRequestFilter {

    /** Request attribute holding the {@code System.nanoTime()} of the request's arrival. */
    public static final String START_NANOS_ATTRIBUTE = RequestStartFilter.class.getName() + ".startNanos";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        request.setAttribute(START_NANOS_ATTRIBUTE, System.nanoTime());
        filterChain.doFilter(request, response);
    }
}
