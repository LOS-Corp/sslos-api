package com.laundry.attachment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.UUID;

/** Nhân viên / chủ cửa hàng tải ảnh kiểm đồ, ảnh đồ bỏ quên, chứng từ chuyển khoản. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Tệp đính kèm")
public class AttachmentController {

    private final AttachmentService service;

    public record AttachmentView(UUID id, String ownerType, UUID ownerId, String purpose, String contentType,
                                 long sizeBytes, Instant createdAt) {
        public static AttachmentView of(Attachment a) {
            return new AttachmentView(a.getId(), a.getOwnerType(), a.getOwnerId(), a.getPurpose(), a.getContentType(),
                    a.getSizeBytes(), a.getCreatedAt());
        }
    }

    @PostMapping(value = "/api/staff/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tải tệp lên (ảnh ≤ 10 MB hoặc PDF) cho một đối tượng")
    public AttachmentView upload(@RequestParam String ownerType, @RequestParam UUID ownerId,
                                 @RequestParam String purpose, @RequestParam MultipartFile file) {
        return AttachmentView.of(service.store(ownerType, ownerId, purpose, file));
    }

    @GetMapping("/api/staff/attachments/{id}")
    public ResponseEntity<Resource> download(@PathVariable UUID id) {
        Attachment a = service.get(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(a.getContentType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .body(new FileSystemResource(service.file(a)));
    }
}
