package com.notificationapp.authentication.model;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String password; // Encrypted

    @Enumerated(EnumType.STRING)
    private UserTier tier = UserTier.FREE;

    public User() {}

    public User(String username, String password, UserTier tier) {
        this.username = username;
        this.password = password;
        this.tier = tier;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public UserTier getTier() { return tier; }
}
