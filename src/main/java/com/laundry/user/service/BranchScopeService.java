package com.laundry.user.service;

import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import com.laundry.shared.security.CurrentUser;
import com.laundry.shared.security.Role;
import com.laundry.user.repository.BranchAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Branch scope of the caller (BR-01, FR-AUTH-03). Owner and Admin are chain-wide; Staff only reach
 * the branches the Owner assigned — deny by default. Customers never pass through here: their data
 * is filtered by ownership instead.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BranchScopeService {

    private final BranchAssignmentRepository assignments;

    public boolean canAccess(CurrentUser user, UUID branchId) {
        if (user.isChainWide()) {
            return true;
        }
        return user.has(Role.STAFF) && branchId != null
                && assignments.existsByIdUserIdAndIdBranchId(user.id(), branchId);
    }

    public void require(CurrentUser user, UUID branchId) {
        if (!canAccess(user, branchId)) {
            throw BusinessException.of(ErrorCode.BRANCH_OUT_OF_SCOPE,
                    "You do not have permission for this branch");
        }
    }

    /** Branches a list endpoint may return: empty = no filter (chain-wide), otherwise the assigned set. */
    public Optional<Set<UUID>> visibleBranches(CurrentUser user) {
        if (user.isChainWide()) {
            return Optional.empty();
        }
        if (!user.has(Role.STAFF)) {
            return Optional.of(Set.of());
        }
        return Optional.of(assignments.findBranchIdsByUserId(user.id()));
    }
}
