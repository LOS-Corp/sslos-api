package com.laundry.attachment.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure rules of {@link AttachmentService}: type sniffing, file names and references. */
class AttachmentRulesTest {

    @Test
    void detectsTypeFromTheSignatureOnly() {
        assertThat(AttachmentService.detectType(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).contains("image/jpeg");
        assertThat(AttachmentService.detectType(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})).contains("image/png");
        assertThat(AttachmentService.detectType("%PDF-1.7".getBytes())).contains("application/pdf");
        assertThat(AttachmentService.detectType("<html>".getBytes())).isEmpty();
        assertThat(AttachmentService.detectType(new byte[]{'P', 'K', 3, 4})).as("zip/docx").isEmpty();
    }

    @Test
    void keepsReadableNamesButNeverPathsOrForeignExtensions() {
        assertThat(AttachmentService.safeName("C:\\Users\\a\\ủy nhiệm chi.JPG", "image/jpeg")).isEqualTo("ủy nhiệm chi.jpg");
        assertThat(AttachmentService.safeName("../../evil.exe", "application/pdf")).isEqualTo("evil.pdf");
        assertThat(AttachmentService.safeName("<>:*?.png", "image/png")).isEqualTo("chung-tu.png");
        assertThat(AttachmentService.safeName(null, "image/png")).isEqualTo("chung-tu.png");
    }

    @Test
    void findsEveryReferenceInFreeText() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertThat(AttachmentService.referencedIds("attachment:" + a + " · VCB FT123 · attachment:" + b)).containsExactly(a, b);
        assertThat(AttachmentService.referencedIds("VCB FT2630112345")).isEmpty();
        assertThat(AttachmentService.referencedIds(null)).isEmpty();
    }
}
