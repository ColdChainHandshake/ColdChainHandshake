-- ============================================================
-- COLD CHAIN HANDSHAKE — SHARED GPS MONITORING SCHEMA
-- Table: shipment_locations
-- Purpose: Real-time Phone A -> Phone B foreground GPS sharing
-- ============================================================

CREATE TABLE IF NOT EXISTS shipment_locations (
    id TEXT PRIMARY KEY,
    shipment_id TEXT NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    accuracy REAL NOT NULL,
    timestamp BIGINT NOT NULL
);

-- Index on shipment_id and timestamp for efficient latest location queries
CREATE INDEX IF NOT EXISTS idx_shipment_locations_lookup 
ON shipment_locations (shipment_id, timestamp DESC);
