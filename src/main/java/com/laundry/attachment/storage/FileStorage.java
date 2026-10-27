package com.laundry.attachment.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * Where evidence bytes live. The business code only knows opaque keys, so a local-disk store can be
 * swapped for object storage (MinIO / S3) without touching the attachment rules.
 */
public interface FileStorage {

    /** Stores {@code content} under {@code key}; a key is written once and never overwritten. */
    void put(String key, byte[] content) throws IOException;

    InputStream open(String key) throws IOException;
}
