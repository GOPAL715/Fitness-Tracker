package com.fittrack.api;
import com.fittrack.app.OwnedResourceService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/v1")
public class OwnedResourceController {
 private final OwnedResourceService service; public OwnedResourceController(OwnedResourceService s){service=s;}
 @GetMapping("/{resource:profile|fitness-profile|daily-metrics|body-metrics|workouts|workout-sessions|workout-templates|plan-sessions|personal-records|goals|meals|food-scans|ai-usage|habits|habit-logs|reminders|health-devices|coach-notifications|exercises|foods|workout-exercises|exercise-sets|workout-template-exercises|meal-items|food-scan-items}") public List<Map<String,Object>> list(@PathVariable String resource,@AuthenticationPrincipal String u){return service.list(resource,u);}
 @PostMapping("/{resource:profile|fitness-profile|daily-metrics|body-metrics|workouts|workout-sessions|workout-templates|plan-sessions|personal-records|goals|meals|food-scans|ai-usage|habits|habit-logs|reminders|health-devices|coach-notifications}") public Map<String,Object> create(@PathVariable String resource,@RequestBody Map<String,Object> b,@AuthenticationPrincipal String u){return service.create(resource,b,u);}
 @PutMapping("/{resource:fitness-profile|daily-metrics|body-metrics|workouts|workout-sessions|workout-templates|plan-sessions|personal-records|goals|meals|food-scans|ai-usage|habits|habit-logs|reminders|health-devices|coach-notifications}") public Map<String,Object> update(@PathVariable String resource,@PathVariable String id,@RequestBody Map<String,Object>b,@AuthenticationPrincipal String u){return service.update(resource,id,b,u);}
  @PatchMapping("/{resource:fitness-profile|daily-metrics|body-metrics|workouts|workout-sessions|workout-templates|plan-sessions|personal-records|goals|meals|food-scans|ai-usage|habits|habit-logs|reminders|health-devices|coach-notifications}/{id}") public Map<String,Object> patch(@PathVariable String resource,@PathVariable String id,@RequestBody Map<String,Object>b,@AuthenticationPrincipal String u){return service.update(resource,id,b,u);}
 @DeleteMapping("/{resource:fitness-profile|daily-metrics|body-metrics|workouts|workout-sessions|workout-templates|plan-sessions|personal-records|goals|meals|food-scans|ai-usage|habits|habit-logs|reminders|health-devices|coach-notifications}") public void delete(@PathVariable String resource,@PathVariable String id,@AuthenticationPrincipal String u){service.delete(resource,id,u);}
 @GetMapping("/{resource}/{id}") public Map<String,Object> get(@PathVariable String resource,@PathVariable String id,@AuthenticationPrincipal String u){return service.get(resource,id,u);}
}
