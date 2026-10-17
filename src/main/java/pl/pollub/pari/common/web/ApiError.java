package pl.pollub.pari.common.web;

import java.time.Instant;

/** Single error body for every failed request, as the frontend expects (errorCode, message, statusCode, timestamp). */
public record ApiError(String errorCode, String message, int statusCode, Instant timestamp) {
}
