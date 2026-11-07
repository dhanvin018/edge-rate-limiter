package com.notificationapp.authentication;

import com.notificationapp.authentication.controller.AuthController;
import com.notificationapp.authentication.services.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationModeWiringTest {

    @Nested
    @SpringBootTest(properties = "authentication.required=false")
    class GatewayMode {

        @Autowired
        private ApplicationContext context;

        @Test
        @DisplayName("auth=false: the app starts without auth endpoints or a database")
        void startsWithoutAuthBeans() {
            assertThat(context.getBeanNamesForType(AuthController.class)).isEmpty();
            assertThat(context.getBeanNamesForType(UserRepository.class)).isEmpty();
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "authentication.required=true",
            "spring.datasource.url=jdbc:h2:mem:auth-mode-wiring;MODE=PostgreSQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop"
    })
    class AuthenticationMode {

        @Autowired
        private ApplicationContext context;

        @Test
        @DisplayName("auth=true: the app starts with auth endpoints and the user repository")
        void startsWithAuthBeans() {
            assertThat(context.getBeanNamesForType(AuthController.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(UserRepository.class)).hasSize(1);
        }
    }
}
