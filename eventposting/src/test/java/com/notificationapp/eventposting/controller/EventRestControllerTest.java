package com.notificationapp.eventposting.controller;

import com.notificationapp.eventposting.model.EventRequest;
import com.notificationapp.eventposting.service.EventPublisherService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventRestControllerTest {

    private final EventPublisherService publisher = mock(EventPublisherService.class);
    private final EventRestController controller = new EventRestController(publisher);
    private final EventRequest request = new EventRequest("user@example.com", "hello");

    @Test
    @DisplayName("Publishes the event and returns the publisher's result")
    void publishesEvent() throws Exception {
        when(publisher.publish("user@example.com", "hello")).thenReturn("Event queued for user@example.com");

        ResponseEntity<String> response = controller.postEvent(request);

        verify(publisher).publish("user@example.com", "hello");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("Event queued for user@example.com");
    }

    @Test
    @DisplayName("Does not report success when publishing fails")
    void publishFailureIsNotReportedAsSuccess() throws Exception {
        when(publisher.publish("user@example.com", "hello"))
                .thenThrow(new ExecutionException("broker unavailable", new RuntimeException()));

        assertThatThrownBy(() -> controller.postEvent(request)).isInstanceOf(ExecutionException.class);
    }
}
