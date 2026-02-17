package com.studysync.profile;

import com.studysync.profile.dto.ProfileResponse;
import com.studysync.profile.dto.UpdateProfileRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public ProfileResponse getProfile(Authentication authentication) {
        return profileService.getProfile(authentication.getName());
    }

    @PatchMapping
    public ProfileResponse updateProfile(
            Authentication authentication,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        return profileService.updateDisplayName(authentication.getName(), request);
    }

    @PostMapping("/avatar")
    public ProfileResponse uploadAvatar(
            Authentication authentication,
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        return profileService.uploadAvatar(authentication.getName(), file);
    }
}
