package com.laundry.kiosk;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/kiosks")
@RequiredArgsConstructor
@Tag(name = "Admin · Kiosk")
public class AdminKioskController {

    private final KioskAuthService service;

    public record CreateKiosk(@NotBlank @Size(max = 20) String code, @NotBlank @Size(max = 120) String name) {
    }

    public record ReasonBody(@NotBlank String reason) {
    }

    @GetMapping
    public List<KioskAuthService.KioskView> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo kiosk; khóa X-Kiosk-Key chỉ trả một lần, cài vào kiosk ngay")
    public KioskAuthService.KioskWithKey create(@Valid @RequestBody CreateKiosk body) {
        return service.create(body.code().trim(), body.name().trim());
    }

    @PostMapping("/{id}/rotate-key")
    public KioskAuthService.KioskWithKey rotate(@PathVariable UUID id, @Valid @RequestBody ReasonBody body) {
        return service.rotateKey(id, body.reason());
    }

    @PostMapping("/{id}/disable")
    public KioskAuthService.KioskView disable(@PathVariable UUID id, @Valid @RequestBody ReasonBody body) {
        return service.setStatus(id, false, body.reason());
    }

    @PostMapping("/{id}/enable")
    public KioskAuthService.KioskView enable(@PathVariable UUID id, @Valid @RequestBody ReasonBody body) {
        return service.setStatus(id, true, body.reason());
    }
}
