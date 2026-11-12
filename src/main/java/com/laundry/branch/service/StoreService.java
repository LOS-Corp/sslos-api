package com.laundry.branch.service;

import com.laundry.branch.entity.Branch;
import com.laundry.branch.entity.BranchStatus;
import com.laundry.branch.repository.BranchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/**
 * Single-store mode. The system runs on a local server inside one store, so there is exactly one
 * store. It is still stored as a row of {@code branches} so machines, bookings, refunds and the
 * booking configuration keep their {@code branch_id}. Nobody picks a branch any more: every
 * request without one goes to this store.
 *
 * <p>Which row is the store: {@code app.store.id} when set, otherwise the seeded
 * {@code 00000000-0000-0000-0000-000000000001} (V21), otherwise the oldest active row. An empty
 * table gets a fresh store, so a new database works without any setup.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StoreService {

    public static final UUID SEEDED_STORE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final String DEFAULT_NAME = "Cửa hàng LaundryOS";

    private final BranchRepository branches;

    @Value("${app.store.id:}")
    private String configuredId = "";

    private volatile UUID cachedId;

    /** The store's id. */
    public UUID id() {
        UUID id = cachedId;
        if (id != null && branches.existsById(id)) {
            return id;
        }
        id = current().getId();
        cachedId = id;
        return id;
    }

    /** {@code requested} if given, otherwise the store (old clients still send a branchId). */
    public UUID idOr(UUID requested) {
        return requested != null ? requested : id();
    }

    @Transactional
    public Branch current() {
        if (configuredId != null && !configuredId.isBlank()) {
            UUID wanted = UUID.fromString(configuredId.trim());
            return branches.findById(wanted).orElseThrow(() -> new IllegalStateException(
                    "app.store.id " + wanted + " is not a row of branches"));
        }
        Optional<Branch> seeded = branches.findById(SEEDED_STORE_ID);
        if (seeded.isPresent()) {
            return seeded.get();
        }
        return branches.findAll().stream()
                .sorted(Comparator.comparing((Branch b) -> b.getStatus() != BranchStatus.ACTIVE)
                        .thenComparing(Branch::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .findFirst()
                .orElseGet(this::createStore);
    }

    private Branch createStore() {
        Branch store = branches.save(Branch.builder().name(DEFAULT_NAME).status(BranchStatus.ACTIVE).build());
        log.info("No store yet: created '{}' ({})", store.getName(), store.getId());
        return store;
    }
}
