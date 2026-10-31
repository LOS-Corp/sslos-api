package com.laundry.attachment.controller;

import com.laundry.attachment.service.AttachmentService;
import com.laundry.attachment.service.AttachmentService.AttachmentContent;
import com.laundry.attachment.service.AttachmentService.Purpose;
import com.laundry.attachment.service.AttachmentService.StoredAttachment;
import com.laundry.shared.security.CurrentUser;
import com.laundry.shared.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Evidence files for withdrawals (UC-13) and exception refunds (UC-08 4a). */
@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
@Tag(name = "Attachments", description = "Transfer proofs and refund evidence (JPEG, PNG, PDF, at most 5 MB)")
public class AttachmentController {

    private final AttachmentService attachments;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.OPERATOR)
    @Operation(summary = "Upload an evidence file; refer to it as the returned `reference` in the proof or evidence text")
    public StoredAttachment upload(@RequestParam("file") MultipartFile file, @RequestParam Purpose purpose,
                                   CurrentUser uploader) throws IOException {
        return attachments.upload(file.getBytes(), file.getOriginalFilename(), purpose, uploader);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Download a file the caller may see (Owner: all; Staff: own or their branches' refunds; "
            + "customer: proofs of their own withdrawals)")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID id, CurrentUser viewer) {
        AttachmentContent content = attachments.read(id, viewer);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.sizeBytes())
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(content.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(content.stream()));
    }
}
