ALTER TABLE user_roles
    ADD COLUMN new_user_id UUID;

UPDATE user_roles ur
SET new_user_id = u.public_user_id
FROM users u
WHERE ur.user_id = u.id;

ALTER TABLE user_roles
    ALTER COLUMN new_user_id SET NOT NULL;

DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    SELECT conname
    INTO constraint_name
    FROM pg_constraint
    WHERE conrelid = 'user_roles'::regclass
      AND contype = 'f'
      AND confrelid = 'users'::regclass;

    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE user_roles DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;

ALTER TABLE user_roles
    DROP CONSTRAINT IF EXISTS user_roles_pkey,
    DROP COLUMN user_id;

ALTER TABLE user_roles
    RENAME COLUMN new_user_id TO user_id;

ALTER TABLE users
    DROP CONSTRAINT IF EXISTS users_pkey,
    DROP COLUMN id;

ALTER TABLE users
    RENAME COLUMN public_user_id TO id;

ALTER TABLE users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

ALTER TABLE user_roles
    ADD CONSTRAINT user_roles_pkey PRIMARY KEY (user_id, role_id),
    ADD CONSTRAINT user_roles_user_fk FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;