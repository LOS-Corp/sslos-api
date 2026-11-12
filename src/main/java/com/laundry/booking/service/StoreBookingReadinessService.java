package com.laundry.booking.service;

import com.laundry.store.entity.StoreSettings;
import com.laundry.store.service.StoreService;

import com.laundry.shared.error.ErrorCode;

import com.laundry.shared.error.BusinessException;

import com.laundry.booking.dto.BookingConfigDtos.StoreReadiness;
import com.laundry.booking.dto.BookingConfigDtos.ResourceCounts;
import com.laundry.booking.dto.BookingConfigDtos.ServiceReadiness;
import com.laundry.booking.dto.BookingConfigDtos.ValidationIssue;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineStatus;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.repository.MachineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;


@Service
@RequiredArgsConstructor
public class StoreBookingReadinessService {
    private final JdbcTemplate jdbc;
    private final StoreService store;
    private final MachineRepository machineRepository;

    /** Whether customers can book at the store, and what is still missing (BR-28). */
    @Transactional
    public StoreReadiness evaluate() {
        return evaluate(store.settings());
    }

    @Transactional
    public boolean isCustomerEligible(String serviceCode) {
        StoreReadiness readiness = evaluate();
        return readiness.ready() && readiness.services().stream()
                .anyMatch(service -> service.code().equals(serviceCode)
                        && service.enabled() && service.ready());
    }

    private StoreReadiness evaluate(StoreSettings settings) {
        List<ValidationIssue> issues = new ArrayList<>();
        String timezone = settings.getTimezone();
        boolean settingsValid = timezone != null && isValidTimezone(timezone);
        if (timezone == null) {
            issues.add(issue("BOOKING_SETTINGS_MISSING", "Chưa cấu hình múi giờ đặt lịch.",
                    "Thiết lập múi giờ cho cửa hàng.", null));
        } else if (!settingsValid) {
            issues.add(issue("INVALID_TIMEZONE", "Múi giờ cửa hàng không phải IANA timezone hợp lệ.",
                    "Chọn múi giờ hợp lệ, ví dụ Asia/Ho_Chi_Minh.", null));
        }

        Integer hours = jdbc.queryForObject("SELECT COUNT(*) FROM store_hours", Integer.class);
        boolean hoursValid = hours != null && hours > 0;
        if (!hoursValid) {
            issues.add(issue("BOOKING_HOURS_MISSING", "Chưa thiết lập ngày và giờ hoạt động.",
                    "Cấu hình ít nhất một khoảng giờ hoạt động.", null));
        }

        List<Machine> machines = machineRepository.findAll();
        int washers = countMachines(machines, MachineType.WASHER);
        int dryers = countMachines(machines, MachineType.DRYER);
        int onlineWashers = countOperationalMachines(machines, MachineType.WASHER);
        int onlineDryers = countOperationalMachines(machines, MachineType.DRYER);
        int ironStations = countResources("IRON_STATION");
        int lockers = countResources("LOCKER");
        ResourceCounts counts = new ResourceCounts(
                washers, dryers, onlineWashers, onlineDryers, ironStations, lockers);

        List<ServiceReadiness> services = jdbc.query("""
                SELECT d.code, d.name, d.service_mode, d.processing_minutes,
                       d.secondary_processing_minutes, d.check_in_minutes,
                       d.transfer_minutes, d.checkout_minutes,
                       o.enabled, o.price, o.processing_minutes_override,
                       o.secondary_processing_minutes_override
                FROM booking_service_definitions d
                LEFT JOIN service_offerings o ON o.service_code = d.code
                WHERE d.active = TRUE
                ORDER BY CASE d.service_mode WHEN 'SELF_SERVICE' THEN 0 ELSE 1 END, d.name
                """, (rs, row) -> {
            boolean configured = rs.getObject("enabled") != null;
            boolean enabled = configured && rs.getBoolean("enabled");
            java.math.BigDecimal price = rs.getBigDecimal("price");
            int processing = rs.getObject("processing_minutes_override") == null
                    ? rs.getInt("processing_minutes")
                    : rs.getInt("processing_minutes_override");
            int secondary = rs.getObject("secondary_processing_minutes_override") == null
                    ? rs.getInt("secondary_processing_minutes")
                    : rs.getInt("secondary_processing_minutes_override");
            String code = rs.getString("code");
            List<ValidationIssue> serviceIssues = new ArrayList<>();
            if (!configured) {
                serviceIssues.add(issue("SERVICE_NOT_CONFIGURED", "Dịch vụ chưa được cấu hình tại cửa hàng.",
                        "Bật dịch vụ và đặt giá.", code));
            } else if (enabled) {
                if (price == null || price.signum() <= 0) {
                    serviceIssues.add(issue("SERVICE_PRICE_INVALID", "Giá dịch vụ phải lớn hơn 0.",
                            "Nhập giá hợp lệ cho dịch vụ.", code));
                }
                for (String type : requirements(code)) {
                    int available = switch (type) {
                        case "WASHER" -> washers;
                        case "DRYER" -> dryers;
                        case "IRON_STATION" -> ironStations;
                        case "LOCKER" -> lockers;
                        default -> 0;
                    };
                    if (available == 0) {
                        String issueCode = switch (type) {
                            case "WASHER" -> "WASHER_MISSING";
                            case "DRYER" -> "DRYER_MISSING";
                            case "IRON_STATION" -> "IRON_STATION_MISSING";
                            case "LOCKER" -> "LOCKER_MISSING";
                            default -> "RESOURCE_MISSING";
                        };
                        serviceIssues.add(issue(issueCode, "Thiếu tài nguyên " + type + " đang bật.",
                                "Thêm hoặc bật tài nguyên phù hợp cho cửa hàng.", code));
                    }
                }
            }
            boolean serviceReady = configured && enabled && price != null && price.signum() > 0
                    && serviceIssues.isEmpty();
            return new ServiceReadiness(code, rs.getString("name"), rs.getString("service_mode"),
                    configured, enabled, price, processing, secondary, rs.getInt("check_in_minutes"),
                    rs.getInt("transfer_minutes"), rs.getInt("checkout_minutes"),
                    serviceReady, serviceIssues);
        });

        int configuredServices = (int) services.stream().filter(ServiceReadiness::configured).count();
        int enabledServices = (int) services.stream().filter(ServiceReadiness::enabled).count();
        boolean hasReadyService = services.stream().anyMatch(ServiceReadiness::ready);
        boolean ready = settingsValid && hoursValid && hasReadyService;
        return new StoreReadiness(settings.getName(), timezone, settingsValid, hoursValid, configuredServices, enabledServices,
                counts, ready, List.copyOf(issues), services);
    }

    private int countMachines(List<Machine> machines, MachineType type) {
        return (int) machines.stream().filter(machine -> machine.getType() == type).count();
    }

    private int countOperationalMachines(List<Machine> machines, MachineType type) {
        return (int) machines.stream()
                .filter(machine -> machine.getType() == type && machine.isOnline()
                        && machine.isCommissioned()
                        && (machine.getStatus() == MachineStatus.AVAILABLE
                        || machine.getStatus() == MachineStatus.RESERVED))
                .count();
    }

    private int countResources(String type) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM service_resources WHERE resource_type = ? AND enabled = TRUE
                """, Integer.class, type);
        return count == null ? 0 : count;
    }

    private List<String> requirements(String serviceCode) {
        return jdbc.query("""
                SELECT resource_type FROM booking_service_requirements
                WHERE service_code = ? ORDER BY sequence_no
                """, (rs, row) -> rs.getString(1), serviceCode);
    }

    private boolean isValidTimezone(String timezone) {
        try {
            ZoneId.of(timezone);
            return true;
        } catch (DateTimeException exception) {
            return false;
        }
    }

    private ValidationIssue issue(String code, String message, String action, String serviceCode) {
        return new ValidationIssue(code, message, action, serviceCode);
    }
}
