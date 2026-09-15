-- Purchase Transaction Core
-- V11: Initial transaction schema
-- Modified to be idempotent for repair scenarios

CREATE TABLE IF NOT EXISTS service_types (
    id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL,
    service_type VARCHAR(50) NOT NULL REFERENCES service_types(id),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING_PAYMENT',
    total_amount DECIMAL(10,2) NOT NULL,
    expires_at TIMESTAMP,
    paid_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT chk_transaction_status CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'EXPIRED'))
);

CREATE INDEX IF NOT EXISTS idx_transactions_customer ON transactions(customer_id);
CREATE INDEX IF NOT EXISTS idx_transactions_status ON transactions(status);
CREATE INDEX IF NOT EXISTS idx_transactions_expires ON transactions(expires_at);

CREATE TABLE IF NOT EXISTS machine_reservations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id),
    machine_id UUID NOT NULL,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    status VARCHAR(30) NOT NULL DEFAULT 'RESERVED',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT chk_reservation_status CHECK (status IN ('RESERVED', 'CONFIRMED', 'ACTIVE', 'COMPLETED', 'CANCELLED', 'EXPIRED'))
);

CREATE INDEX IF NOT EXISTS idx_machine_reservations_machine ON machine_reservations(machine_id);
CREATE INDEX IF NOT EXISTS idx_machine_reservations_transaction ON machine_reservations(transaction_id);
CREATE INDEX IF NOT EXISTS idx_machine_reservations_time ON machine_reservations(start_time, end_time);

CREATE TABLE IF NOT EXISTS laundry_service_orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id),
    customer_name VARCHAR(100),
    customer_phone VARCHAR(20),
    weight_kg DECIMAL(5,2),
    service_notes TEXT,
    estimated_completion TIMESTAMP,
    actual_completion TIMESTAMP,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT chk_order_status CHECK (status IN ('PENDING', 'RECEIVED', 'PROCESSING', 'READY', 'COMPLETED', 'CANCELLED'))
);

CREATE INDEX IF NOT EXISTS idx_laundry_orders_transaction ON laundry_service_orders(transaction_id);

CREATE TABLE IF NOT EXISTS drop_off_locker_reservations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    laundry_order_id UUID NOT NULL REFERENCES laundry_service_orders(id),
    locker_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'RESERVED',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT chk_locker_reservation_status CHECK (status IN ('RESERVED', 'CONFIRMED', 'USED', 'EXPIRED', 'CANCELLED'))
);

CREATE INDEX IF NOT EXISTS idx_drop_off_locker_order ON drop_off_locker_reservations(laundry_order_id);
CREATE INDEX IF NOT EXISTS idx_drop_off_locker_locker ON drop_off_locker_reservations(locker_id);

-- Insert default service types (idempotent)
INSERT INTO service_types (id, name, description) VALUES 
    ('SELF_SERVICE', 'Tự giặt', 'Khách hàng tự vận hành máy giặt'),
    ('LAUNDRY_SERVICE', 'Giặt ủi', 'Dịch vụ giặt ủi complete')
ON CONFLICT (id) DO NOTHING;
