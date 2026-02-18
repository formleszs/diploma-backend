package com.studysync.controller;

import com.studysync.service.AuthService;
import com.studysync.entity.dto.response.AuthResponse;
import com.studysync.entity.dto.request.LoginRequest;
import com.studysync.entity.dto.request.RegisterRequest;
import com.studysync.entity.dto.response.UserResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest req) {
        return authService.register(req);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    @GetMapping("/me")
    public UserResponse me(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new SecurityException("Unauthorized");
        }
        return authService.me(auth.getName());
    }
}
