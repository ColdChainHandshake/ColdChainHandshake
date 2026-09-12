-- ============================================================
-- COLD CHAIN HANDSHAKE — SHARED GPS MONITORING SCHEMA
-- Table: shipment_locations
-- Purpose: Real-time Phone A -> Phone B foreground GPS sharing
-- ============================================================

-- 1. Create table
CREATE TABLE IF NOT EXISTS public.shipment_locations (
    id TEXT PRIMARY KEY,
    shipment_id TEXT NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    accuracy REAL NOT NULL,
    timestamp BIGINT NOT NULL
);

-- 2. Index on shipment_id and timestamp for efficient latest location queries
CREATE INDEX IF NOT EXISTS idx_shipment_locations_lookup 
ON public.shipment_locations (shipment_id, timestamp DESC);

-- 3. Grant table permissions to PostgREST roles (anon, authenticated, service_role)
GRANT ALL ON TABLE public.shipment_locations TO anon, authenticated, service_role;

-- 4. Enable Row Level Security (RLS) and grant permissive prototype policies
ALTER TABLE public.shipment_locations ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Allow public select on shipment_locations" ON public.shipment_locations;
CREATE POLICY "Allow public select on shipment_locations"
ON public.shipment_locations FOR SELECT
TO anon, authenticated, service_role
USING (true);

DROP POLICY IF EXISTS "Allow public insert on shipment_locations" ON public.shipment_locations;
CREATE POLICY "Allow public insert on shipment_locations"
ON public.shipment_locations FOR INSERT
TO anon, authenticated, service_role
WITH CHECK (true);

DROP POLICY IF EXISTS "Allow public update on shipment_locations" ON public.shipment_locations;
CREATE POLICY "Allow public update on shipment_locations"
ON public.shipment_locations FOR UPDATE
TO anon, authenticated, service_role
USING (true)
WITH CHECK (true);

-- 5. Reload PostgREST schema cache
NOTIFY pgrst, 'reload schema';
