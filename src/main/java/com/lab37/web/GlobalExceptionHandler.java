package com.lab37.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Last line of defense for exceptions escaping controllers. Extends
 * ResponseEntityExceptionHandler so standard Spring MVC exceptions (type
 * mismatches, unreadable bodies, validation failures, …) keep their proper
 * 4xx statuses; everything else is logged at ERROR with the full stack trace
 * — the signal a log aggregator (Splunk → PagerDuty) alerts on — and mapped
 * to an opaque 500 so internals don't leak to clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(Exception.class)
	ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
		log.error("Unhandled exception processing {} {}", request.getMethod(),
				request.getRequestURI(), e);
		return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
				"Internal server error");
	}
}
