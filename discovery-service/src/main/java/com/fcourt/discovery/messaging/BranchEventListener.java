package com.fcourt.discovery.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.discovery.persistence.BranchProjection;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class BranchEventListener {
    private final ObjectMapper mapper;
    private final Validator validator;
    private final BranchProjection projection;

    public BranchEventListener(ObjectMapper mapper, Validator validator, BranchProjection projection) {
        this.mapper = mapper;
        this.validator = validator;
        this.projection = projection;
    }

    @KafkaListener(topics = "${discovery.kafka.branch-topic}")
    public void consume(String payload) throws JsonProcessingException {
        var event = mapper.readValue(payload, BranchUpdatedEvent.class);
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        var violations = validator.validate(event);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        projection.apply(event);
    }
}
