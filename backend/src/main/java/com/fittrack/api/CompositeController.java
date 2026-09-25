package com.fittrack.api;

import com.fittrack.app.CompositeDtos.*;
import com.fittrack.app.CompositeService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class CompositeController {
    private final CompositeService service;
    public CompositeController(CompositeService service) { this.service = service; }

    @PostMapping("/workout-sessions/complete")
    public CompositeResponse completeSession(@Valid @RequestBody WorkoutSessionCompleteRequest request, @AuthenticationPrincipal String user) { return service.completeSession(request, user); }
    @PostMapping("/workout-templates/complete")
    public CompositeResponse completeTemplate(@Valid @RequestBody WorkoutTemplateCompleteRequest request, @AuthenticationPrincipal String user) { return service.completeTemplate(request, user); }
    @PostMapping("/meals/complete")
    public CompositeResponse completeMeal(@Valid @RequestBody MealCompleteRequest request, @AuthenticationPrincipal String user) { return service.completeMeal(request, user); }
}
