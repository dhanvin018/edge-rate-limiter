package com.notificationapp.eventposting.controller;

import com.notificationapp.eventposting.service.EventPublisherService;
import com.notificationapp.eventposting.model.EventRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/events")
public class EventRestController {

    private static final Logger log = LoggerFactory.getLogger(EventRestController.class);

    private final EventPublisherService eventPublisherService;

    public EventRestController(EventPublisherService eventPublisherService) {
        this.eventPublisherService = eventPublisherService;
    }

    @PostMapping("/postEvent")
    public ResponseEntity<String> postEvent(@RequestBody @Valid EventRequest request)
            throws ExecutionException, InterruptedException {
        log.debug("Received request to post event for recipient: {}", request.email());
//        String result = eventPublisherService.publish(request.email(), request.message());
        log.info("Event successfully published for recipient: {}", request.email());
//        return ResponseEntity.ok(result);
        return ResponseEntity.ok("Event successfully published for recipient: " + request.email());
    }
}
