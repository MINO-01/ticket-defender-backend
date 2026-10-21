package com.ticket.defender_core.application.service;

public record ValidatedImage(byte[] bytes, String mediaType, long byteSize, String sha256) {}
