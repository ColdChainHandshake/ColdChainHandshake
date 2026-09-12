-- ============================================================
-- COLD CHAIN HANDSHAKE — SHIPMENT CUSTODY & DEVICE TRANSFER SCHEMA
-- Table: shipment_custody
-- Purpose: Tracks authoritative physical custody of a consignment across devices
-- ============================================================

CREATE TABLE IF NOT EXISTS shipment_custody (
    shipment_id TEXT PRIMARY KEY,
    active_device_id TEXT NOT NULL,
    custody_state TEXT NOT NULL,
    updated_at BIGINT NOT NULL
);

-- Index on active_device_id and custody_state for fast custody queries
CREATE INDEX IF NOT EXISTS idx_shipment_custody_device 
ON shipment_custody (active_device_id, custody_state);
