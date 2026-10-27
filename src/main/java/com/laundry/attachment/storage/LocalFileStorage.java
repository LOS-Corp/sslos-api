package com.laundry.attachment.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** File store on the local disk ({@code app.storage.local-dir}); keys are generated, never user input. */
@Component
public class LocalFileStorage implements FileStorage {

    private final Path root;

    public LocalFileStorage(@Value("${app.storage.local-dir:./data/uploads}") String rootDir) {
        this.root = Path.of(rootDir).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] content) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Files.write(target, content, StandardOpenOption.CREATE_NEW);
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    /** Defence in depth: a key can never point outside the root. */
    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return target;
    }
}
