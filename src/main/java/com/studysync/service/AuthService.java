package com.studysync.service;

import com.studysync.entity.User;
import com.studysync.entity.dto.request.LoginRequest;
import com.studysync.entity.dto.request.RegisterRequest;
import com.studysync.entity.dto.response.AuthResponse;
import com.studysync.entity.dto.response.UserResponse;
import com.studysync.security.JwtService;
import com.studysync.enums.Role;
import com.studysync.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authManager;

    public AuthService(UserRepository users,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       AuthenticationManager authManager) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authManager = authManager;
    }

    public AuthResponse register(RegisterRequest req) {
        String email = req.getEmail().trim().toLowerCase();

        if (users.existsByEmailIgnoreCase(email)) {
            log.error("Email already in use: {}",email);
            throw new IllegalArgumentException("Email already in use");
        }

        User u = new User(email, passwordEncoder.encode(req.getPassword()), Role.USER);
        u.setDisplayName(req.getDisplayName());
        u.setAvatarUrl(null);
        u = users.save(u);

        log.info("user with email: {} successfully registered", email);
        String token = jwtService.generateToken(u.getEmail(), Map.of("role", u.getRole().name(), "uid", u.getId()));
        return AuthResponse.builder()
                .token(token)
                .user(toUserResponse(u))
                .build();
    }

    public AuthResponse login(LoginRequest req) {
        Authentication auth = authManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.getEmail(), req.getPassword())
        );
        SecurityContextHolder.getContext().setAuthentication(auth);

        User u = users.findByEmailIgnoreCase(req.getEmail())
                .orElseThrow(() -> new SecurityException("Invalid credentials"));

        log.info("user with email: {} successfully logged in",u.getEmail());
        String token = jwtService.generateToken(u.getEmail(), Map.of("role", u.getRole().name(), "uid", u.getId()));
        return AuthResponse.builder()
                .token(token)
                .user(toUserResponse(u))
                .build();
    }

    public UserResponse me(String email) {
        User u = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new SecurityException("User not found"));
        return toUserResponse(u);
    }

    private UserResponse toUserResponse(User u) {
        return UserResponse.builder()
                .id(u.getId())
                .email(u.getEmail())
                .displayName(u.getDisplayName())
                .avatarUrl(u.getAvatarUrl())
                .build();
    }
}
