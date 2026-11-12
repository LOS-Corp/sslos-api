package com.laundry.user.service;

import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import com.laundry.shared.security.CurrentUser;
import com.laundry.shared.security.Role;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Store scope of the caller (BR-01, FR-AUTH-03). Single-store mode: there is one store, so every
 * operator (Admin, Owner, Staff) works on all of it and Staff are no longer assigned to branches.
 * Customers never pass through here: their data is filtered by ownership instead.
 */
@Service
public class BranchScopeService {

    public boolean canAccess(CurrentUser user, UUID branchId) {
        return isOperator(user);
    }

    public void require(CurrentUser user, UUID branchId) {
        if (!canAccess(user, branchId)) {
            throw BusinessException.of(ErrorCode.BRANCH_OUT_OF_SCOPE,
                    "You do not have permission for this store");
        }
    }

    /** Branches a list endpoint may return: empty = no filter (any operator), otherwise none. */
    public Optional<Set<UUID>> visibleBranches(CurrentUser user) {
        return isOperator(user) ? Optional.empty() : Optional.of(Set.of());
    }

    private static boolean isOperator(CurrentUser user) {
        return user.isChainWide() || user.has(Role.STAFF);
    }
}
