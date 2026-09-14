-- QR Token Schema
-- V13: QR token tables

CREATE TABLE qr_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token VARCHAR(64) NOT NULL UNIQUE,
    type VARCHAR(30) NOT NULL,
    reference_id UUID NOT NULL,
    reference_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    issued_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP,
    used_by VARCHAR(100),
    revoked_at TIMESTAMP,
    revoked_reason TEXT,
    
    CONSTRAINT chk_qr_type CHECK (type IN ('MACHINE_ACTIVATION', 'LOCKER_DROP_OFF', 'LOCKER_PICKUP')),
    CONSTRAINT chk_qr_status CHECK (status IN ('ACTIVE', 'USED', 'EXPIRED', 'REVOKED'))
);

CREATE INDEX idx_qr_tokens_token ON qr_tokens(token);
CREATE INDEX idx_qr_tokens_reference ON qr_tokens(reference_id, reference_type);
CREATE INDEX idx_qr_tokens_status ON qr_tokens(status);
CREATE INDEX idx_qr_tokens_expires ON qr_tokens(expires_at);
