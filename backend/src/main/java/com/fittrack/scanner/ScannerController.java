package com.fittrack.scanner;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/food-scans")
public class ScannerController {
    private final ScannerService service;
    public ScannerController(ScannerService service) { this.service = service; }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<ScannerService.ScanView> create(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal String userId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(file, userId));
    }
    @GetMapping public List<ScannerService.ScanView> list(@AuthenticationPrincipal String userId) { return service.list(userId); }
    @GetMapping("/{id}") public ScannerService.ScanView get(@PathVariable UUID id, @AuthenticationPrincipal String userId) { return service.get(id, userId); }
    @PutMapping("/{id}/items")
    public ScannerService.ScanView items(@PathVariable UUID id, @Valid @RequestBody List<@Valid ItemCorrection> items, @AuthenticationPrincipal String userId) {
        return service.correct(id, userId, items);
    }
    @PostMapping("/{id}/confirm")
    public ScannerService.ScanView confirm(@PathVariable UUID id, @RequestBody(required = false) ConfirmRequest request, @AuthenticationPrincipal String userId) {
        return service.confirm(id, userId, request == null ? new ConfirmRequest(null, null, null) : request);
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal String userId) {
        service.delete(id, userId); return ResponseEntity.noContent().build();
    }

    public record ItemCorrection(@NotNull UUID itemId, UUID foodId,
        @Size(max=160) String name,
        @NotNull @DecimalMin("1.0") @DecimalMax("5000.0") BigDecimal grams) {}
    public record ConfirmRequest(LocalDate mealDate, String mealType, String mealName) {}
}