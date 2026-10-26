package com.laundry.booking.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.booking.dto.MachineProgramDtos;
import com.laundry.booking.dto.MachineProgramDtos.MachineProgramDto;
import com.laundry.booking.dto.MachineProgramDtos.MachineProgramRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Wash / dry program catalogue. A booking picks one program per machine it
 * uses; the program sets the machine's run time (so the slot length), adds its
 * surcharge to the price, and is sent to the device in AUTHORIZE_START.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MachineProgramService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    private static final String COLUMNS = """
            code, machine_type, name, description, surcharge, main_minutes, finish_minutes,
            drum_run_seconds, drum_pause_seconds, heat_percent, uv_enabled, is_default, sort_order, active
            """;

    @Transactional(readOnly = true)
    public List<MachineProgramDto> listActive(String machineType) {
        if (machineType == null || machineType.isBlank()) {
            return jdbc.query("SELECT " + COLUMNS + " FROM machine_programs WHERE active = TRUE ORDER BY machine_type DESC, sort_order, name",
                    (rs, row) -> map(rs));
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM machine_programs WHERE active = TRUE AND machine_type = ? ORDER BY sort_order, name",
                (rs, row) -> map(rs), machineType.toUpperCase());
    }

    @Transactional(readOnly = true)
    public List<MachineProgramDto> listAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM machine_programs ORDER BY machine_type DESC, sort_order, name",
                (rs, row) -> map(rs));
    }

    @Transactional
    public MachineProgramDto create(MachineProgramRequest request) {
        if (request.code() == null || request.machineType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã và loại máy là bắt buộc");
        }
        if (find(request.code()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã chương trình đã tồn tại");
        }
        validate(request.machineType(), request);
        lockType(request.machineType());
        boolean makeDefault = request.defaultProgram() || defaultFor(request.machineType()).isEmpty();
        if (makeDefault) clearDefault(request.machineType());
        boolean washer = "WASHER".equals(request.machineType());
        jdbc.update("""
                INSERT INTO machine_programs
                  (code, machine_type, name, description, surcharge, main_minutes, finish_minutes,
                   drum_run_seconds, drum_pause_seconds, heat_percent, uv_enabled, is_default, sort_order, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, request.code(), request.machineType(), request.name().trim(), description(request),
                request.surcharge(), request.mainMinutes(), request.finishMinutes(),
                washer ? request.drumRunSeconds() : null, washer ? request.drumPauseSeconds() : null,
                washer ? null : request.heatPercent(), washer ? null : request.uvEnabled(),
                makeDefault, request.sortOrder(), makeDefault || request.active());
        return find(request.code()).orElseThrow();
    }

    @Transactional
    public MachineProgramDto update(String code, MachineProgramRequest request) {
        MachineProgramDto existing = find(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy chương trình"));
        String type = existing.machineType();
        validate(type, request);
        lockType(type);
        if (existing.defaultProgram() && (!request.defaultProgram() || !request.active())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Chọn chương trình mặc định khác trước khi bỏ mặc định hoặc tắt chương trình này");
        }
        if (request.defaultProgram() && !request.active()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chương trình mặc định phải đang bật");
        }
        if (request.defaultProgram()) clearDefault(type);
        boolean washer = "WASHER".equals(type);
        jdbc.update("""
                UPDATE machine_programs SET name = ?, description = ?, surcharge = ?, main_minutes = ?,
                    finish_minutes = ?, drum_run_seconds = ?, drum_pause_seconds = ?, heat_percent = ?,
                    uv_enabled = ?, is_default = ?, sort_order = ?, active = ?, updated_at = NOW()
                WHERE code = ?
                """, request.name().trim(), description(request), request.surcharge(), request.mainMinutes(),
                request.finishMinutes(), washer ? request.drumRunSeconds() : null,
                washer ? request.drumPauseSeconds() : null, washer ? null : request.heatPercent(),
                washer ? null : request.uvEnabled(), request.defaultProgram(), request.sortOrder(),
                request.active(), code);
        return find(code).orElseThrow();
    }

    /**
     * The program for every WASHER / DRYER requirement, in requirement order.
     * A blank code picks the type's default. Types without any program (an
     * empty catalogue) are skipped, so the service keeps its own duration.
     */
    List<MachineProgramDto> resolve(List<String> requirements, String washProgram, String dryProgram) {
        List<MachineProgramDto> programs = new ArrayList<>();
        for (String type : requirements) {
            String requested = switch (type) {
                case "WASHER" -> washProgram;
                case "DRYER" -> dryProgram;
                default -> null;
            };
            if (!"WASHER".equals(type) && !"DRYER".equals(type)) continue;
            if (requested == null || requested.isBlank()) {
                defaultFor(type).ifPresent(programs::add);
                continue;
            }
            MachineProgramDto program = find(requested.trim().toUpperCase())
                    .filter(MachineProgramDto::active)
                    .filter(candidate -> candidate.machineType().equals(type))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Chương trình không hợp lệ cho " + ("WASHER".equals(type) ? "máy giặt" : "máy sấy")));
            programs.add(program);
        }
        return programs;
    }

    String toJson(List<MachineProgramDto> programs) {
        try {
            return objectMapper.writeValueAsString(programs);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize booking programs", e);
        }
    }

    List<MachineProgramDto> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Unreadable booking programs snapshot: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * The {@code program} object of AUTHORIZE_START for this machine: the
     * program the booking paid for, else the type's default. Empty when nothing
     * can be resolved; the firmware then runs its built-in default program.
     */
    public Optional<Map<String, Object>> devicePayload(UUID transactionId, String machineType) {
        try {
            Optional<MachineProgramDto> program = Optional.empty();
            if (transactionId != null) {
                program = jdbc.query("SELECT programs::text FROM scheduled_bookings WHERE transaction_id = ?",
                                (rs, row) -> rs.getString(1), transactionId).stream()
                        .flatMap(json -> fromJson(json).stream())
                        .filter(candidate -> machineType.equals(candidate.machineType()))
                        .findFirst();
            }
            int timeDivisor = timeDivisor();
            return program.or(() -> defaultFor(machineType)).map(MachineProgramService::toDevicePayload)
                    .map(payload -> { payload.put("timeDivisor", timeDivisor); return payload; });
        } catch (DataAccessException e) {
            log.warn("Program lookup failed for transaction {}: {}", transactionId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Platform-wide run mode sent with every program; 1 (real minutes) when unset. */
    public int timeDivisor() {
        try {
            return jdbc.query("SELECT setting_value FROM app_settings WHERE setting_key = 'program.time_divisor'",
                            (rs, row) -> rs.getString(1)).stream()
                    .map(Integer::parseInt).findFirst()
                    .filter(value -> value >= 1 && value <= MachineProgramDtos.MAX_TIME_DIVISOR).orElse(1);
        } catch (DataAccessException | NumberFormatException e) {
            log.warn("Program time divisor unavailable, using real time: {}", e.getMessage());
            return 1;
        }
    }

    @Transactional
    public int setTimeDivisor(int timeDivisor) {
        jdbc.update("""
                INSERT INTO app_settings (setting_key, setting_value, updated_at)
                VALUES ('program.time_divisor', ?, NOW())
                ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value, updated_at = NOW()
                """, String.valueOf(timeDivisor));
        log.info("Program time divisor set to {}", timeDivisor);
        return timeDivisor;
    }

    static Map<String, Object> toDevicePayload(MachineProgramDto program) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", program.code());
        payload.put("name", program.name());
        if ("WASHER".equals(program.machineType())) {
            payload.put("washSeconds", program.mainMinutes() * 60);
            payload.put("spinSeconds", program.finishMinutes() * 60);
            payload.put("drumRunSeconds", program.drumRunSeconds());
            payload.put("drumPauseSeconds", program.drumPauseSeconds());
        } else {
            payload.put("drySeconds", program.mainMinutes() * 60);
            payload.put("coolSeconds", program.finishMinutes() * 60);
            payload.put("heatPercent", program.heatPercent());
            payload.put("uv", Boolean.TRUE.equals(program.uvEnabled()));
        }
        return payload;
    }

    private Optional<MachineProgramDto> find(String code) {
        return jdbc.query("SELECT " + COLUMNS + " FROM machine_programs WHERE code = ?", (rs, row) -> map(rs), code)
                .stream().findFirst();
    }

    private Optional<MachineProgramDto> defaultFor(String machineType) {
        return jdbc.query("SELECT " + COLUMNS + " FROM machine_programs WHERE machine_type = ? AND is_default AND active",
                (rs, row) -> map(rs), machineType).stream().findFirst();
    }

    private void clearDefault(String machineType) {
        jdbc.update("UPDATE machine_programs SET is_default = FALSE, updated_at = NOW() WHERE machine_type = ? AND is_default",
                machineType);
    }

    /** Serialises default changes per machine type. */
    private void lockType(String machineType) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, "machine-programs:" + machineType);
    }

    private static void validate(String machineType, MachineProgramRequest request) {
        if ("WASHER".equals(machineType)) {
            if (request.drumRunSeconds() == null || request.drumPauseSeconds() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Máy giặt cần thời gian quay và nghỉ của lồng");
            }
        } else if ("DRYER".equals(machineType)) {
            if (request.heatPercent() == null || request.uvEnabled() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Máy sấy cần mức nhiệt và chế độ UV");
            }
            if (request.finishMinutes() < 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Máy sấy cần ít nhất 1 phút làm nguội");
            }
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loại máy không hợp lệ");
        }
    }

    private static String description(MachineProgramRequest request) {
        return request.description() == null ? "" : request.description().trim();
    }

    private static MachineProgramDto map(ResultSet rs) throws SQLException {
        int main = rs.getInt("main_minutes");
        int finish = rs.getInt("finish_minutes");
        return new MachineProgramDto(
                rs.getString("code"), rs.getString("machine_type"), rs.getString("name"),
                rs.getString("description"), rs.getBigDecimal("surcharge"), main, finish, main + finish,
                rs.getObject("drum_run_seconds", Integer.class), rs.getObject("drum_pause_seconds", Integer.class),
                rs.getObject("heat_percent", Integer.class), rs.getObject("uv_enabled", Boolean.class),
                rs.getBoolean("is_default"), rs.getInt("sort_order"), rs.getBoolean("active"));
    }
}
