package com.fittrack.api;

import com.fittrack.app.AppDataService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AppDataController {
    private final AppDataService service;
    public AppDataController(AppDataService service) { this.service = service; }

    @GetMapping("/app-data")
    public Map<String, Object> appData(@AuthenticationPrincipal String userId) {
        return service.load(userId);
    }
}
