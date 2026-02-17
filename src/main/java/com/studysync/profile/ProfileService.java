package com.studysync.profile;

import com.studysync.profile.dto.ProfileResponse;
import com.studysync.profile.dto.UpdateProfileRequest;
import com.studysync.user.User;
import com.studysync.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Slf4j
@Service
public class ProfileService {

    private final UserRepository userRepository;

    @Value("${app.upload.avatar-dir:./uploads/avatars}")
    private String avatarDir;

    public ProfileService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public ProfileResponse getProfile(String email) {
        User user = findUser(email);
        return toResponse(user);
    }

    public ProfileResponse updateDisplayName(String email, UpdateProfileRequest request) {
        User user = findUser(email);
        user.setDisplayName(request.getDisplayName());
        userRepository.save(user);

        log.info("Successfully changed name to: {} for user: {}",request.getDisplayName(), user.getId());
        return toResponse(user);
    }

    public ProfileResponse uploadAvatar(String email, MultipartFile file) throws IOException {
        User user = findUser(email);

        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        if (file.getSize() > 2 * 1024 * 1024) {
            throw new IllegalArgumentException("Max file size is 2MB");
        }

        String contentType = file.getContentType();
        if (contentType == null ||
                !(contentType.equals("image/jpeg")
                        || contentType.equals("image/png")
                        || contentType.equals("image/webp"))) {
            throw new IllegalArgumentException("Only jpg, png, webp allowed");
        }

        Path uploadPath = Path.of(avatarDir);
        Files.createDirectories(uploadPath);

        String extension = getExtension(file.getOriginalFilename());
        String fileName = UUID.randomUUID() + extension;

        Path filePath = uploadPath.resolve(fileName);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        user.setAvatarUrl("/uploads/avatars/" + fileName);
        userRepository.save(user);

        log.info("Successfully changed avatar for user: {}", user.getId());
        return toResponse(user);
    }

    private User findUser(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    private ProfileResponse toResponse(User user) {
        return ProfileResponse.builder()
                .id(user.getId())
                .displayName(user.getDisplayName())
                .email(user.getEmail())
                .avatarUrl(user.getAvatarUrl())
                .build();
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf("."));
    }
}
