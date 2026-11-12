package com.laundry.iot.firmware;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs esptool on the store server:
 * {@code esptool --chip esp32 --port COM3 --baud 460800 --before default_reset --after hard_reset
 * write_flash -z 0x10000 firmware.bin}. The board's auto-reset circuit (DTR/RTS) puts it in
 * the bootloader; only the application is rewritten, the bootloader and partition table stay.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EsptoolFlasher implements FirmwareFlasher {

    private final FirmwareProperties properties;

    @Override
    public int flash(String port, Path image, Consumer<String> output) throws Exception {
        List<String> command = new ArrayList<>(esptoolCommand());
        command.addAll(List.of("--chip", "esp32", "--port", port, "--baud", String.valueOf(properties.getBaudRate()),
                "--before", "default_reset", "--after", "hard_reset",
                "write_flash", "-z", properties.getAppOffset(), image.toString()));
        log.info("[FIRMWARE] {}", String.join(" ", command));

        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("PYTHONUNBUFFERED", "1");   // stream progress lines as they happen
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        Process process;
        try {
            process = builder.start();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot run esptool (" + command.getFirst()
                    + "); install it with `pip install esptool` or set ESPTOOL_COMMAND", e);
        }
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (!line.isBlank()) output.accept(line.strip());
                }
            } catch (java.io.IOException ignored) {
                // process ended
            }
        });
        if (!process.waitFor(properties.getFlashTimeoutSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            reader.join(2000);
            throw new IllegalStateException("esptool did not finish within " + properties.getFlashTimeoutSeconds() + " s");
        }
        reader.join(2000);
        return process.exitValue();
    }

    List<String> esptoolCommand() {
        if (!properties.getEsptool().isEmpty()) return properties.getEsptool();
        Path pio = Path.of(System.getProperty("user.home"), ".platformio");
        Path script = pio.resolve("packages/tool-esptoolpy/esptool.py");
        for (Path python : List.of(pio.resolve("penv/Scripts/python.exe"), pio.resolve("penv/bin/python"))) {
            if (Files.isRegularFile(python) && Files.isRegularFile(script)) {
                return List.of(python.toString(), script.toString());
            }
        }
        return List.of("python", "-m", "esptool");
    }
}
