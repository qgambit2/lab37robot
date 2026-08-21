package com.lab37.web;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Outermost servlet filter, ordered first so every later filter runs inside
 * it. Controller exceptions are handled by {@link GlobalExceptionHandler}
 * inside the DispatcherServlet and never reach here — this catches what that
 * handler can't see: exceptions thrown by other filters before the request
 * reaches a controller (last-mile validation, security, idempotency, …).
 * Logs at ERROR with the stack trace, then rethrows so the container's error
 * handling still applies.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestErrorLoggingFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(RequestErrorLoggingFilter.class);

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		try {
			filterChain.doFilter(request, response);
		} catch (Exception e) {
			log.error("Request {} {} failed in the filter chain (before/outside controller)",
					request.getMethod(), request.getRequestURI(), e);
			throw e;
		}
	}
}
