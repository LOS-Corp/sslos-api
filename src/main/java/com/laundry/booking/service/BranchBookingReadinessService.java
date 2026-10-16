package com.laundry.booking.service;

import com.laundry.booking.dto.BookingConfigDtos;
import com.laundry.booking.dto.BookingConfigDtos.BranchReadiness;
import com.laundry.booking.dto.BookingConfigDtos.ResourceCounts;
import com.laundry.booking.dto.BookingConfigDtos.ServiceReadiness;
import com.laundry.booking.dto.BookingConfigDtos.ValidationIssue;
import com.laundry.branch.entity.Branch;
import com.laundry.branch.entity.BranchStatus;
import com.laundry.branch.repository.BranchRepository;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineStatus;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.repository.MachineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.laundry.booking.dto.BookingConfigDtos.*;

@Service
@RequiredArgsConstructor
public class BranchBookingReadinessService {
    private final JdbcTemplate jdbc;
    private final BranchRepository branchRepository;
    private final MachineRepository machineRepository;

    @Transactional(readOnly = true)
    public List<BranchReadiness> evaluateAll() {
        return branchRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(Branch::getName,
                        String.CASE_INSENSITIVE_ORDER))
                .map(branch -> evaluate(branch))
                .toList();
    }

    @Transactional(readOnly = true)
    public BranchReadiness evaluate(UUID branchId) {
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
        return evaluate(branch);
    }

    @Transactional(readOnly = true)
    public boolean isCustomerEligible(UUID branchId, String serviceCode) {
        BranchReadiness readiness = evaluate(branchId);
        return readiness.ready() && readiness.services().stream()
                .anyMatch(service -> service.code().equals(serviceCode)
                        && service.enabled() && service.ready());
    }

    private BranchReadiness evaluate(Branch branch) {
        UUID branchId = branch.getId();
        List<ValidationIssue> issues = new ArrayList<>();
        if (branch.getStatus() != BranchStatus.ACTIVE) {
            issues.add(issue("BRANCH_INACTIVE", "Chi nhánh đang ngừng hoạt động.",
                    "Kích hoạt chi nhánh trong mục Quản lý chi nhánh.", null));
        }

        List<String> timezones = jdbc.query("""
                SELECT timezone FROM branch_booking_settings WHERE branch_id = ?
                """, (rs, row) -> rs.getString(1), branchId);
        String timezone = timezones.isEmpty() ? null : timezones.getFirst();
        boolean settingsValid = timezone != null && isValidTimezone(timezone);
        if (timezone == null) {
            issues.add(issue("BOOKING_SETTINGS_MISSING", "Chưa cấu hình múi giờ đặt lịch.",
                    "Thiết lập múi giờ cho chi nhánh.", null));
        } else if (!settingsValid) {
            issues.add(issue("INVALID_TIMEZONE", "Múi giờ chi nhánh không phải IANA timezone hợp lệ.",
                    "Chọn múi giờ hợp lệ, ví dụ Asia/Ho_Chi_Minh.", null));
        }

        Integer hours = jdbc.queryForObject("""
                SELECT COUNT(*) FROM branch_booking_hours WHERE branch_id = ?
                """, Integer.class, branchId);
        boolean hoursValid = hours != null && hours > 0;
        if (!hoursValid) {
            issues.add(issue("BOOKING_HOURS_MISSING", "Chưa thiết lập ngày và giờ hoạt động.",
                    "Cấu hình ít nhất một khoảng giờ hoạt động.", null));
        }

        List<Machine> machines = machineRepository.findByBranchId(branchId);
        int washers = countMachines(machines, MachineType.WASHER);
        int dryers = countMachines(machines, MachineType.DRYER);
        int onlineWashers = countOperationalMachines(machines, MachineType.WASHER);
        int onlineDryers = countOperationalMachines(machines, MachineType.DRYER);
        int ironStations = countResources(branchId, "IRON_STATION");
        int lockers = countResources(branchId, "LOCKER");
        ResourceCounts counts = new ResourceCounts(
                washers, dryers, onlineWashers, onlineDryers, ironStations, lockers);

        List<ServiceReadiness> services = jdbc.query("""
                SELECT d.code, d.name, d.service_mode, d.processing_minutes,
                       d.secondary_processing_minutes, d.check_in_minutes,
                       d.transfer_minutes, d.checkout_minutes,
                       o.enabled, o.price, o.processing_minutes_override,
                       o.secondary_processing_minutes_override
                FROM booking_service_definitions d
                LEFT JOIN branch_service_offerings o
                  ON o.service_code = d.code AND o.branch_id = ?
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
                serviceIssues.add(issue("SERVICE_NOT_CONFIGURED", "Dịch vụ chưa được cấu hình tại chi nhánh.",
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
                                "Thêm hoặc bật tài nguyên phù hợp cho chi nhánh.", code));
                    }
                }
            }
            boolean serviceReady = configured && enabled && price != null && price.signum() > 0
                    && serviceIssues.isEmpty();
            return new ServiceReadiness(code, rs.getString("name"), rs.getString("service_mode"),
                    configured, enabled, price, processing, secondary, rs.getInt("check_in_minutes"),
                    rs.getInt("transfer_minutes"), rs.getInt("checkout_minutes"),
                    serviceReady, serviceIssues);
        }, branchId);

        int configuredServices = (int) services.stream().filter(ServiceReadiness::configured).count();
        int enabledServices = (int) services.stream().filter(ServiceReadiness::enabled).count();
        boolean hasReadyService = services.stream().anyMatch(ServiceReadiness::ready);
        boolean ready = branch.getStatus() == BranchStatus.ACTIVE && settingsValid && hoursValid
                && hasReadyService;
        return new BranchReadiness(branchId, branch.getName(), branch.getStatus().name(),
                timezone, settingsValid, hoursValid, configuredServices, enabledServices,
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

    private int countResources(UUID branchId, String type) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM branch_service_resources
                WHERE branch_id = ? AND resource_type = ? AND enabled = TRUE
                """, Integer.class, branchId, type);
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
