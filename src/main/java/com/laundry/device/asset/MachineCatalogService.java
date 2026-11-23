package com.laundry.device.asset;

import com.laundry.audit.AuditService;
import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Danh mục hãng máy và model (Quản trị viên): chọn khi duyệt bo để ghi tài sản. */
@Service
@RequiredArgsConstructor
public class MachineCatalogService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    /** machineType: WASHER / DRYER, null = dùng cho cả hai. deviceCount > 0 thì không xóa được. */
    public record ModelView(UUID id, UUID brandId, String name, String machineType, int deviceCount) {
    }

    public record BrandView(UUID id, String name, List<ModelView> models) {
    }

    @Transactional(readOnly = true)
    public List<BrandView> list() {
        Map<UUID, List<ModelView>> models = new HashMap<>();
        jdbc.query("""
                select m.id, m.brand_id, m.name, m.machine_type,
                       (select count(*) from devices d where d.model_id = m.id) as device_count
                from machine_models m order by lower(m.name)
                """, rs -> {
            UUID brandId = rs.getObject(2, UUID.class);
            models.computeIfAbsent(brandId, k -> new ArrayList<>()).add(new ModelView(rs.getObject(1, UUID.class), brandId,
                    rs.getString(3), rs.getString(4), rs.getInt(5)));
        });
        List<BrandView> brands = new ArrayList<>();
        jdbc.query("select id, name from machine_brands order by lower(name)", rs -> {
            UUID id = rs.getObject(1, UUID.class);
            brands.add(new BrandView(id, rs.getString(2), models.getOrDefault(id, List.of())));
        });
        return brands;
    }

    @Transactional
    public BrandView createBrand(String rawName) {
        String name = clean(rawName, "Tên hãng");
        UUID id = UUID.randomUUID();
        unique(() -> jdbc.update("insert into machine_brands (id, name) values (:id, :name)",
                new MapSqlParameterSource("id", id).addValue("name", name)), "Đã có hãng “" + name + "” trong danh mục");
        audit.user("MACHINE_BRAND_CREATED", "machine_brand", id.toString(), "Thêm hãng " + name, null, Map.of("name", name));
        return new BrandView(id, name, List.of());
    }

    @Transactional
    public BrandView renameBrand(UUID id, String rawName) {
        String name = clean(rawName, "Tên hãng");
        String before = brandName(id);
        unique(() -> jdbc.update("update machine_brands set name = :name, updated_at = now() where id = :id",
                new MapSqlParameterSource("id", id).addValue("name", name)), "Đã có hãng “" + name + "” trong danh mục");
        audit.user("MACHINE_BRAND_RENAMED", "machine_brand", id.toString(), "Đổi tên hãng " + before + " thành " + name,
                Map.of("name", before), Map.of("name", name));
        return list().stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteBrand(UUID id) {
        String name = brandName(id);
        Integer models = jdbc.queryForObject("select count(*) from machine_models where brand_id = :id", Map.of("id", id), Integer.class);
        if (models != null && models > 0) throw new BusinessException(ErrorCode.CONFLICT, "Xóa hết model của hãng " + name + " trước");
        jdbc.update("delete from machine_brands where id = :id", Map.of("id", id));
        audit.user("MACHINE_BRAND_DELETED", "machine_brand", id.toString(), "Xóa hãng " + name, Map.of("name", name), null);
    }

    @Transactional
    public ModelView createModel(UUID brandId, String rawName, String machineType) {
        brandName(brandId);
        String name = clean(rawName, "Tên model");
        String type = type(machineType);
        UUID id = UUID.randomUUID();
        unique(() -> jdbc.update("insert into machine_models (id, brand_id, name, machine_type) values (:id, :brand, :name, :type)",
                        new MapSqlParameterSource("id", id).addValue("brand", brandId).addValue("name", name).addValue("type", type)),
                "Hãng này đã có model “" + name + "”");
        audit.user("MACHINE_MODEL_CREATED", "machine_model", id.toString(), "Thêm model " + name, null, typed(name, type));
        return new ModelView(id, brandId, name, type, 0);
    }

    @Transactional
    public ModelView updateModel(UUID id, String rawName, String machineType) {
        ModelView before = model(id);
        String name = clean(rawName, "Tên model");
        String type = type(machineType);
        if (type != null && !type.equals(before.machineType())) {
            Integer mismatched = jdbc.queryForObject("select count(*) from devices where model_id = :id and device_type <> :type",
                    new MapSqlParameterSource("id", id).addValue("type", type), Integer.class);
            if (mismatched != null && mismatched > 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "Đang có bo khác loại dùng model này, không đổi loại máy được");
            }
        }
        unique(() -> jdbc.update("update machine_models set name = :name, machine_type = :type, updated_at = now() where id = :id",
                        new MapSqlParameterSource("id", id).addValue("name", name).addValue("type", type)),
                "Hãng này đã có model “" + name + "”");
        audit.user("MACHINE_MODEL_UPDATED", "machine_model", id.toString(), "Sửa model " + name,
                typed(before.name(), before.machineType()), typed(name, type));
        return model(id);
    }

    @Transactional
    public void deleteModel(UUID id) {
        ModelView m = model(id);
        if (m.deviceCount() > 0) throw new BusinessException(ErrorCode.CONFLICT, "Model " + m.name() + " đang được " + m.deviceCount() + " bo dùng");
        jdbc.update("delete from machine_models where id = :id", Map.of("id", id));
        audit.user("MACHINE_MODEL_DELETED", "machine_model", id.toString(), "Xóa model " + m.name(), typed(m.name(), m.machineType()), null);
    }

    ModelView model(UUID id) {
        List<ModelView> found = jdbc.query("""
                select m.id, m.brand_id, m.name, m.machine_type, (select count(*) from devices d where d.model_id = m.id)
                from machine_models m where m.id = :id
                """, Map.of("id", id), (rs, i) -> new ModelView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getInt(5)));
        if (found.isEmpty()) throw BusinessException.notFound("model máy");
        return found.getFirst();
    }

    private String brandName(UUID id) {
        List<String> found = jdbc.queryForList("select name from machine_brands where id = :id", Map.of("id", id), String.class);
        if (found.isEmpty()) throw BusinessException.notFound("hãng máy");
        return found.getFirst();
    }

    private static String clean(String raw, String label) {
        String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + " không được để trống");
        if (name.length() > 80) throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + " tối đa 80 ký tự");
        return name;
    }

    private static String type(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim().toUpperCase();
        if (!t.equals("WASHER") && !t.equals("DRYER")) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Loại máy là WASHER hoặc DRYER");
        return t;
    }

    private static Map<String, Object> typed(String name, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("machineType", type);
        return m;
    }

    private static void unique(Runnable write, String message) {
        try {
            write.run();
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.CONFLICT, message);
        }
    }
}
