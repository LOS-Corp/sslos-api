package com.laundry.attachment.service;

import com.laundry.attachment.storage.FileStorage;
import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import com.laundry.shared.security.CurrentUser;
import com.laundry.shared.security.Role;
import com.laundry.user.service.BranchScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evidence files: withdrawal transfer proofs (UC-13, BR-15) and exception-refund evidence (UC-08 4a).
 * <ul>
 *   <li>Only JPEG, PNG and PDF up to 5 MB; the type is read from the file's signature, not trusted from the client.</li>
 *   <li>The index row is append-only (V50 trigger) and keeps a SHA-256, so evidence cannot change after the fact.</li>
 *   <li>Business rows refer to a file as {@code attachment:<id>} in their existing text column.</li>
 *   <li>Reading: the Owner reads any file; Staff read what they uploaded or the evidence of refunds in their
 *       branches; a customer reads only the proof of their own withdrawals. Anyone else gets 404.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    public static final String REF_PREFIX = "attachment:";
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Pattern REFERENCE = Pattern.compile(REF_PREFIX + "([0-9a-f-]{36})");

    public enum Purpose { WITHDRAWAL_PROOF, REFUND_EVIDENCE }

    public record StoredAttachment(UUID id, String purpose, String fileName, String contentType, long sizeBytes,
                                   String reference) {
    }

    public record AttachmentContent(String fileName, String contentType, long sizeBytes, InputStream stream) {
    }

    private final FileStorage storage;
    private final JdbcTemplate jdbc;
    private final BranchScopeService branchScope;
    private final Clock clock;

    @Transactional
    public StoredAttachment upload(byte[] content, String originalName, Purpose purpose, CurrentUser uploader) {
        if (content == null || content.length == 0) {
            throw BusinessException.of(ErrorCode.VALIDATION_FAILED, "The file is empty");
        }
        if (content.length > MAX_BYTES) {
            throw BusinessException.of(ErrorCode.FILE_TOO_LARGE, "The file is larger than 5 MB");
        }
        String contentType = detectType(content)
                .orElseThrow(() -> BusinessException.of(ErrorCode.FILE_REJECTED, "Only JPEG, PNG or PDF files are accepted"));
        UUID id = UUID.randomUUID();
        var today = clock.instant().atOffset(ZoneOffset.UTC);
        String key = "%d/%02d/%s".formatted(today.getYear(), today.getMonthValue(), id);
        try {
            storage.put(key, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the file", e);
        }
        String fileName = safeName(originalName, contentType);
        jdbc.update("""
                INSERT INTO attachments (id, purpose, file_name, content_type, size_bytes, sha256, storage_key, uploaded_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, purpose.name(), fileName, contentType, content.length, sha256(content), key, uploader.id());
        log.info("Attachment {} ({}, {} bytes) uploaded by {}", id, purpose, content.length, uploader.id());
        return new StoredAttachment(id, purpose.name(), fileName, contentType, content.length, REF_PREFIX + id);
    }

    @Transactional(readOnly = true)
    public AttachmentContent read(UUID id, CurrentUser viewer) {
        Row row = row(id).filter(found -> mayRead(found, viewer))
                .orElseThrow(() -> BusinessException.notFound("File not found"));
        try {
            return new AttachmentContent(row.fileName(), row.contentType(), row.sizeBytes(), storage.open(row.storageKey()));
        } catch (IOException e) {
            throw new UncheckedIOException("Stored file is missing", e);
        }
    }

    /**
     * Every {@code attachment:<id>} in {@code text} must exist and have been uploaded for {@code purpose};
     * a free-text reference (a bank transfer id) is accepted as it is.
     */
    @Transactional(readOnly = true)
    public void requireReferencesValid(String text, Purpose purpose) {
        for (UUID id : referencedIds(text)) {
            boolean ok = row(id).map(found -> found.purpose().equals(purpose.name())).orElse(false);
            if (!ok) {
                throw BusinessException.of(ErrorCode.VALIDATION_FAILED, "The attached file does not exist");
            }
        }
    }

    static List<UUID> referencedIds(String text) {
        if (text == null) {
            return List.of();
        }
        Matcher matcher = REFERENCE.matcher(text);
        ArrayList<UUID> ids = new ArrayList<>();
        while (matcher.find()) {
            try {
                ids.add(UUID.fromString(matcher.group(1)));
            } catch (IllegalArgumentException ignored) {
                // not a UUID: plain text that happens to look like a reference
            }
        }
        return ids;
    }

    private boolean mayRead(Row row, CurrentUser viewer) {
        String reference = "%" + REF_PREFIX + row.id() + "%";
        if (viewer.has(Role.OWNER)) {
            return true;
        }
        if (viewer.has(Role.STAFF)) {
            if (row.uploadedBy().equals(viewer.id())) {
                return true;
            }
            return jdbc.queryForList("SELECT branch_id FROM refund_requests WHERE evidence LIKE ?", UUID.class, reference)
                    .stream().anyMatch(branch -> branchScope.canAccess(viewer, branch));
        }
        if (viewer.has(Role.CUSTOMER)) {
            Integer own = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM wallet_withdrawals WHERE customer_id = ? AND proof_reference LIKE ?",
                    Integer.class, viewer.id(), reference);
            return own != null && own > 0;
        }
        return false;
    }

    private Optional<Row> row(UUID id) {
        return jdbc.query("""
                SELECT id, purpose, file_name, content_type, size_bytes, storage_key, uploaded_by
                FROM attachments WHERE id = ?
                """, (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("purpose"),
                rs.getString("file_name"), rs.getString("content_type"), rs.getLong("size_bytes"),
                rs.getString("storage_key"), rs.getObject("uploaded_by", UUID.class)), id).stream().findFirst();
    }

    /** The real type from the first bytes (magic numbers); the client's Content-Type is ignored. */
    static Optional<String> detectType(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return Optional.of("image/jpeg");
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return Optional.of("image/png");
        }
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') {
            return Optional.of("application/pdf");
        }
        return Optional.empty();
    }

    /** Keeps letters, digits and a few separators; the extension always matches the detected type. */
    static String safeName(String original, String contentType) {
        String base = original == null ? "" : original.replaceAll(".*[/\\\\]", "");
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = base.replaceAll("[^\\p{L}\\p{N} ._-]", "").trim();
        if (base.isEmpty()) {
            base = "chung-tu";
        }
        if (base.length() > 150) {
            base = base.substring(0, 150);
        }
        String extension = switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            default -> ".pdf";
        };
        return base + extension;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Row(UUID id, String purpose, String fileName, String contentType, long sizeBytes,
                       String storageKey, UUID uploadedBy) {
    }
}
