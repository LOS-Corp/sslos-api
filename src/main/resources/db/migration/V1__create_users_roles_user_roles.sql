CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    customer_id UUID NOT NULL UNIQUE,
    full_name VARCHAR(120) NOT NULL,
    email VARCHAR(254) NOT NULL UNIQUE,
    phone VARCHAR(16) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT users_status_check CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT users_phone_e164_check CHECK (phone ~ '^\+[1-9][0-9]{7,14}$')
);

CREATE TABLE roles (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE
);

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT user_roles_user_fk FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT user_roles_role_fk FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE RESTRICT
);

INSERT INTO roles (name) VALUES ('CUSTOMER');