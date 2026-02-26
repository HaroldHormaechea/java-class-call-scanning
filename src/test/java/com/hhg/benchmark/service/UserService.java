package com.hhg.benchmark.service;

import com.hhg.benchmark.entity.User;
import com.hhg.benchmark.exception.NotFoundException;
import com.hhg.benchmark.exception.ValidationException;
import com.hhg.benchmark.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public User createUser(String username, String email, String passwordHash, String role) {
        if (username == null || username.isBlank()) {
            throw new ValidationException("Username must not be blank");
        }
        if (email == null || email.isBlank()) {
            throw new ValidationException("Email must not be blank");
        }
        if (userRepository.existsByEmail(email)) {
            throw new ValidationException("User with email already exists");
        }
        if (userRepository.existsByUsername(username)) {
            throw new ValidationException("User with username already exists");
        }
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        user.setRole(role != null ? role : "USER");
        user.setActive(true);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    @Transactional
    public User updateUser(Long id, String username, String email) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User", id));
        if (username == null || username.isBlank()) {
            throw new ValidationException("Username must not be blank");
        }
        if (email == null || email.isBlank()) {
            throw new ValidationException("Email must not be blank");
        }
        // Check uniqueness for the new values, excluding the current user
        if (!user.getUsername().equals(username) && userRepository.existsByUsername(username)) {
            throw new ValidationException("Username already exists");
        }
        if (!user.getEmail().equals(email) && userRepository.existsByEmail(email)) {
            throw new ValidationException("Email already exists");
        }
        user.setUsername(username);
        user.setEmail(email);
        user.setUpdatedAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User", id));
        user.setActive(false);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
    }

    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User", id));
    }

    public List<User> findAll() {
        return userRepository.findAll();
    }

    public List<User> findActiveUsers() {
        return userRepository.findByActive(true);
    }

    public User authenticate(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ValidationException("Invalid credentials"));
        if (!user.isActive()) {
            throw new ValidationException("User account is inactive");
        }
        if (!user.getPasswordHash().equals(password)) {
            throw new ValidationException("Invalid credentials");
        }
        return user;
    }

    @Transactional
    public void changePassword(Long id, String oldPassword, String newPassword) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User", id));
        if (!user.getPasswordHash().equals(oldPassword)) {
            throw new ValidationException("Old password does not match");
        }
        if (newPassword == null || newPassword.isBlank()) {
            throw new ValidationException("New password must not be blank");
        }
        user.setPasswordHash(newPassword);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
    }
}
