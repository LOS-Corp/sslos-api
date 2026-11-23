package com.laundry.attachment;

import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.security.Actor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Ảnh và chứng từ: lưu file trên đĩa (APP_UPLOAD_DIR), DB lưu khóa + SHA-256. */
@Service
@RequiredArgsConstructor
public class AttachmentService {

    static final Set<String> OWNER_TYPES = Set.of("LAUNDRY_JOB", "FOUND_ITEM", "WITHDRAWAL", "REFUND", "LAUNDRY_CLAIM");
    static final Set<String> PURPOSES = Set.of("INTAKE_PHOTO", "DAMAGE_PHOTO", "ITEM_PHOTO", "TRANSFER_PROOF", "EVIDENCE");
    private static final Set<String> CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "application/pdf");
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private final AttachmentRepository repository;

    @Value("${app.upload-dir:./data/uploads}")
    private String uploadDir;

    @Transactional
    public Attachment store(String ownerType, UUID ownerId, String purpose, MultipartFile file) {
        if (!OWNER_TYPES.contains(ownerType) || !PURPOSES.contains(purpose)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Loại tệp đính kèm không hợp lệ");
        }
        if (file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Tệp rỗng hoặc lớn hơn 10 MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Chỉ nhận ảnh JPEG/PNG/WebP hoặc PDF");
        }
        UUID id = UUID.randomUUID();
        String key = LocalDate.now() + "/" + id;
        Path target = root().resolve(key).normalize();
        try {
            Files.createDirectories(target.getParent());
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(file.getInputStream(), sha)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            Attachment a = new Attachment();
            a.setId(id);
            a.setOwnerType(ownerType);
            a.setOwnerId(ownerId);
            a.setPurpose(purpose);
            a.setStorageKey(key);
            a.setContentType(contentType);
            a.setSizeBytes(file.getSize());
            a.setSha256(HexFormat.of().formatHex(sha.digest()));
            a.setUploadedBy(Actor.optionalUserId().orElse(null));
            return repository.save(a);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot store upload", e);
        }
    }

    public Attachment get(UUID id) {
        return repository.findById(id).orElseThrow(() -> BusinessException.notFound("tệp đính kèm"));
    }

    public List<Attachment> of(String ownerType, UUID ownerId) {
        return repository.findByOwnerTypeAndOwnerIdOrderByCreatedAt(ownerType, ownerId);
    }

    /** Tệp phải thuộc đúng đối tượng (ví dụ chứng từ của đúng yêu cầu rút). */
    public void requireOwned(UUID attachmentId, String ownerType, UUID ownerId) {
        Attachment a = get(attachmentId);
        if (!a.getOwnerType().equals(ownerType) || !a.getOwnerId().equals(ownerId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Tệp đính kèm không thuộc đối tượng này");
        }
    }

    public Path file(Attachment a) {
        Path p = root().resolve(a.getStorageKey()).normalize();
        if (!p.startsWith(root())) throw new IllegalStateException("Bad storage key");
        return p;
    }

    private Path root() {
        return Path.of(uploadDir).toAbsolutePath().normalize();
    }
}
