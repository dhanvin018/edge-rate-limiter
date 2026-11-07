package com.notificationapp.authentication.controller;

import com.notificationapp.authentication.model.AuthRequest;
import com.notificationapp.authentication.model.AuthResponse;
import com.notificationapp.authentication.model.User;
import com.notificationapp.authentication.model.UserTier;
import com.notificationapp.authentication.services.JwtService;
import com.notificationapp.authentication.services.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@ConditionalOnProperty(name = "authentication.required", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody AuthRequest request) {
        log.debug("Signup request received for username: {}", request.username());

        if (userRepository.existsByUsername(request.username())) {
            log.warn("Signup failed: Username '{}' is already taken", request.username());
            return ResponseEntity.badRequest().body("Username already taken");
        }

        User user = new User(
                request.username(),
                passwordEncoder.encode(request.password()),
                UserTier.FREE // Default tier
        );
        userRepository.save(user);

        log.info("User '{}' registered successfully with tier '{}'", user.getUsername(), user.getTier());

        String token = jwtService.generateToken(user.getUsername(), user.getTier().name());
        return ResponseEntity.ok(new AuthResponse(token, user.getUsername(), user.getTier().name()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody AuthRequest request) {
        log.debug("Login request received for username: {}", request.username());

        Optional<User> userOpt = userRepository.findByUsername(request.username());

        if (userOpt.isEmpty() || !passwordEncoder.matches(request.password(), userOpt.get().getPassword())) {
            log.warn("Authentication failed for username: {}", request.username());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid credentials");
        }

        User user = userOpt.get();
        log.info("User '{}' authenticated successfully", user.getUsername());

        String token = jwtService.generateToken(user.getUsername(), user.getTier().name());
        return ResponseEntity.ok(new AuthResponse(token, user.getUsername(), user.getTier().name()));
    }
}
