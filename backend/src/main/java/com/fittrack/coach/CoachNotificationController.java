package com.fittrack.coach;

import com.fittrack.domain.CoachNotification;
import com.fittrack.domain.repositories.CoachNotificationRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/coach-notifications")
public class CoachNotificationController {
    private final CoachNotificationRepository repository;
    public CoachNotificationController(CoachNotificationRepository repository) { this.repository = repository; }
    @PostMapping("/{id}/read")
    public Map<String,Object> markRead(@PathVariable UUID id, @AuthenticationPrincipal String userId) {
        CoachNotification notification = repository.findByIdAndUserId(id, UUID.fromString(userId))
            .orElseThrow(() -> new NoSuchElementException("Notification not found"));
        notification.setRead(true);
        repository.save(notification);
        return Map.of("id", id, "is_read", true);
    }
}
