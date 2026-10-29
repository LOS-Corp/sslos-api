-- V41: Four-role model (BR-01, decision 27/10/2026): ADMIN, OWNER, STAFF, CUSTOMER — no MANAGER.
-- The Owner manages every branch of the chain; Staff work only in the branches the Owner assigned
-- (FR-AUTH-03, task/09_PHAN_QUYEN_4_VAI_TRO.md).

-- Former MANAGER accounts become OWNER: the Owner is the only management level.
INSERT INTO user_roles (user_id, role_id)
SELECT ur.user_id, owner_role.id
FROM user_roles ur
JOIN roles manager_role ON manager_role.id = ur.role_id AND manager_role.name = 'MANAGER'
CROSS JOIN (SELECT id FROM roles WHERE name = 'OWNER') owner_role
ON CONFLICT DO NOTHING;

DELETE FROM user_roles
WHERE role_id = (SELECT id FROM roles WHERE name = 'MANAGER');

DELETE FROM roles WHERE name = 'MANAGER';

-- Staff ↔ branch scope. Owner and Admin are chain-wide and have no rows here;
-- a Staff account without any row sees no branch at all (deny by default).
CREATE TABLE branch_assignments (
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    branch_id   UUID        NOT NULL REFERENCES branches (id) ON DELETE CASCADE,
    assigned_by UUID        REFERENCES users (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, branch_id)
);

CREATE INDEX idx_branch_assignments_branch ON branch_assignments (branch_id);
