package com.laundry.device.asset;

import com.laundry.audit.AuditService;
import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.device.DeviceAdminService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tài sản của bo: model (hãng đi theo model), giá mua, hạn bảo hành, thông số — xác nhận khi duyệt hoặc sửa sau; và số
 * liệu hoàn vốn của máy đang gắn bo. Chỉ ghi các cột tài sản (V2), không đụng trạng thái liên kết của bo.
 */
@Service
@RequiredArgsConstructor
public class DeviceAssetService {

    private final NamedParameterJdbcTemplate jdbc;
    private final DeviceAdminService devices;
    private final MachineCatalogService catalog;
    private final AuditService audit;

    /** Thông tin xác nhận. warrantyUntil null = không có bảo hành. */
    public record AssetInput(@NotNull UUID modelId,
                             @NotNull @Min(0) @Max(10_000_000_000L) Long purchaseCost,
                             LocalDate warrantyUntil,
                             @Size(max = 500) String technicalSpecs) {
    }

    public record AssetView(String deviceId, UUID modelId, UUID brandId, String manufacturer, String model,
                            String machineType, Long purchaseCost, LocalDate warrantyUntil, String technicalSpecs,
                            Instant updatedAt) {
    }

    /**
     * Hoàn vốn tính từ lúc bo được gắn vào máy hiện tại (since). Doanh thu = giá các lượt máy đã chạy + gói sấy thêm
     * khách đã trả; chưa trừ hoàn tiền vì hoàn tiền ghi theo đơn, không theo máy.
     */
    public record StatsView(String deviceId, String machineCode, Instant since, int maintenanceCount, int rentalCount,
                            long revenue, Long purchaseCost, Integer paybackPercent, Long remaining) {
    }

    private static final String SELECT = """
            select d.id, d.model_id, b.id as brand_id, b.name as brand, m.name as model, m.machine_type,
                   d.purchase_cost_vnd, d.warranty_until, d.technical_specs, d.asset_updated_at
            from devices d
            left join machine_models m on m.id = d.model_id
            left join machine_brands b on b.id = m.brand_id
            """;

    @Transactional(readOnly = true)
    public List<AssetView> list() {
        return jdbc.query(SELECT + " order by d.id", Map.of(), (rs, i) -> view(rs));
    }

    @Transactional(readOnly = true)
    public AssetView get(String deviceId) {
        List<AssetView> found = jdbc.query(SELECT + " where d.id = :id", Map.of("id", deviceId), (rs, i) -> view(rs));
        if (found.isEmpty()) throw BusinessException.notFound("thiết bị");
        return found.getFirst();
    }

    @Transactional
    public AssetView update(String deviceId, AssetInput input, String reason) {
        AssetView before = get(deviceId);
        MachineCatalogService.ModelView model = catalog.model(input.modelId());
        String deviceType = jdbc.queryForObject("select device_type from devices where id = :id", Map.of("id", deviceId), String.class);
        if (model.machineType() != null && !model.machineType().equals(deviceType)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Model " + model.name() + " dành cho "
                    + ("DRYER".equals(model.machineType()) ? "máy sấy" : "máy giặt") + ", không khớp loại bo");
        }
        String specs = input.technicalSpecs() == null || input.technicalSpecs().isBlank() ? null : input.technicalSpecs().trim();
        jdbc.update("""
                update devices set model_id = :model, purchase_cost_vnd = :cost, warranty_until = :warranty,
                                   technical_specs = :specs, asset_updated_at = now()
                where id = :id
                """, new MapSqlParameterSource("id", deviceId).addValue("model", input.modelId())
                .addValue("cost", input.purchaseCost())
                .addValue("warranty", input.warrantyUntil() == null ? null : Date.valueOf(input.warrantyUntil()))
                .addValue("specs", specs));
        AssetView after = get(deviceId);
        audit.user("DEVICE_ASSET_CHANGED", "device", deviceId, reason, snapshot(before), snapshot(after));
        return after;
    }

    /** Duyệt bo (đối chiếu mã XT như cũ) và ghi tài sản trong cùng một giao dịch: thiếu một trong hai thì không có gì. */
    @Transactional
    public DeviceAdminService.DeviceView approve(String deviceId, String fingerprint, AssetInput input) {
        DeviceAdminService.DeviceView approved = devices.approve(deviceId, fingerprint);
        if (input != null) update(deviceId, input, "Xác nhận tài sản khi duyệt bo");
        return approved;
    }

    @Transactional(readOnly = true)
    public StatsView stats(String deviceId) {
        AssetView asset = get(deviceId);
        Map<String, Object> link = jdbc.queryForMap("""
                select d.machine_id, mc.code, d.approved_at,
                       (select max(a.occurred_at) from audit_logs a
                         where a.entity_type = 'device' and a.entity_id = d.id and a.action = 'DEVICE_BOUND'
                           and a.after_data ->> 'machineId' = d.machine_id::text) as bound_at
                from devices d left join machines mc on mc.id = d.machine_id
                where d.id = :id
                """, Map.of("id", deviceId));
        UUID machineId = (UUID) link.get("machine_id");
        Timestamp boundAt = (Timestamp) link.get("bound_at");
        Timestamp approvedAt = (Timestamp) link.get("approved_at");
        Instant since = boundAt != null ? boundAt.toInstant() : approvedAt != null ? approvedAt.toInstant() : null;
        int rentals = 0, maintenance = 0;
        long revenue = 0;
        if (machineId != null) {
            MapSqlParameterSource p = new MapSqlParameterSource("machine", machineId)
                    .addValue("since", Timestamp.from(since == null ? Instant.EPOCH : since));
            Map<String, Object> run = jdbc.queryForMap("""
                    select count(*) as runs,
                           coalesce(sum(l.price_amount), 0)
                           + coalesce((select sum(x.price_amount) from dryer_extensions x
                                       join order_legs l2 on l2.id = x.order_leg_id
                                       where l2.machine_id = :machine and l2.started_at >= :since
                                         and x.status in ('PAID', 'APPLIED')), 0) as revenue
                    from order_legs l
                    where l.machine_id = :machine and l.started_at is not null and l.status <> 'CANCELLED'
                      and l.started_at >= :since
                    """, p);
            rentals = ((Number) run.get("runs")).intValue();
            revenue = ((Number) run.get("revenue")).longValue();
            Integer windows = jdbc.queryForObject("""
                    select count(*) from machine_reservations
                    where machine_id = :machine and kind = 'MAINTENANCE' and active and created_at >= :since
                    """, p, Integer.class);
            maintenance = windows == null ? 0 : windows;
        }
        Long cost = asset.purchaseCost();
        Integer percent = cost == null || cost == 0 ? null : (int) Math.min(9_999, Math.floor(revenue * 100.0 / cost));
        Long remaining = cost == null ? null : Math.max(0, cost - revenue);
        return new StatsView(deviceId, (String) link.get("code"), since, maintenance, rentals, revenue, cost, percent, remaining);
    }

    private static AssetView view(ResultSet rs) throws SQLException {
        Date warranty = rs.getDate("warranty_until");
        Timestamp updated = rs.getTimestamp("asset_updated_at");
        long rawCost = rs.getLong("purchase_cost_vnd");
        Long cost = rs.wasNull() ? null : rawCost;
        return new AssetView(rs.getString("id"), rs.getObject("model_id", UUID.class), rs.getObject("brand_id", UUID.class),
                rs.getString("brand"), rs.getString("model"), rs.getString("machine_type"), cost,
                warranty == null ? null : warranty.toLocalDate(), rs.getString("technical_specs"),
                updated == null ? null : updated.toInstant());
    }

    private static Map<String, Object> snapshot(AssetView a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("modelId", a.modelId() == null ? null : a.modelId().toString());
        m.put("manufacturer", a.manufacturer());
        m.put("model", a.model());
        m.put("purchaseCost", a.purchaseCost());
        m.put("warrantyUntil", a.warrantyUntil() == null ? null : a.warrantyUntil().toString());
        m.put("technicalSpecs", a.technicalSpecs());
        return m;
    }
}
