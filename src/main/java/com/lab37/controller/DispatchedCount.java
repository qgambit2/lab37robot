package com.lab37.controller;

/** One minute's robot dispatch count, minute in yyyyMMddHHmm format. */
public record DispatchedCount(String minute, int count) {
}
