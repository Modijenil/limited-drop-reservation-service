package com.limiteddrop.reservation.service;

public interface OutboxService {

    void enqueue(String aggregateType, String aggregateId, String eventType, String payload);

    int publishPending();
}
