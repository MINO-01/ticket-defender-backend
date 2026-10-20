package com.ticket.defender_core.application.service;

public interface EvidenceObjectStorage {
    void checkReady();
    String put(String objectKey, ValidatedImage image);
    void delete(String objectKey, String objectVersion);
}
