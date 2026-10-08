-- V15: Create refund_requests table
-- SRS: FR-PAY-05, FR-PAY-06, FR-PAY-07, FR-PAY-08 - Refund Request Management
-- SRS: BR-RFD-001 - Separation of duty (Staff tạo, Manager duyệt)
-- SRS: BR-RFD-002 - Branch fund ownership và traceability

CREATE TABLE refund_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID NOT NULL,
    order_id UUID,
    transaction_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    requested_by UUID NOT NULL,
    approved_by UUID,
    amount DECIMAL(10, 2) NOT NULL CHECK (amount > 0),
    reason TEXT NOT NULL,
    evidence TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    rejection_reason TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    approved_at TIMESTAMP,
    completed_at TIMESTAMP,
    
    -- FK to payments table (exists in V12)
    CONSTRAINT fk_refund_payment FOREIGN KEY (payment_id) REFERENCES payments(id)
    -- NOTE: FK to branches and users will be added when branch/user modules are created
);

-- Index for status lookup
CREATE INDEX idx_refund_requests_status ON refund_requests(status);

-- Index for branch lookup
CREATE INDEX idx_refund_requests_branch_id ON refund_requests(branch_id);

-- Index for payment lookup
CREATE INDEX idx_refund_requests_payment_id ON refund_requests(payment_id);

-- Index for requester lookup
CREATE INDEX idx_refund_requests_requested_by ON refund_requests(requested_by);

-- Index for pending approval lookup
CREATE INDEX idx_refund_requests_pending ON refund_requests(branch_id, status) 
    WHERE status IN ('CREATED', 'PENDING_APPROVAL');

COMMENT ON TABLE refund_requests IS 'Refund requests created by Staff and approved by Manager';
COMMENT ON COLUMN refund_requests.status IS 'CREATED, PENDING_APPROVAL, APPROVED, REJECTED, PROCESSING, COMPLETED, FAILED';
