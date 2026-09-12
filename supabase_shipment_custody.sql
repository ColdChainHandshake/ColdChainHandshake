-- ============================================================
-- COLD CHAIN HANDSHAKE — SHIPMENT CUSTODY & DEVICE TRANSFER SCHEMA
-- Table: shipment_custody
-- Purpose: Tracks authoritative physical custody of a consignment across devices
-- ============================================================

-- 1. Create table
CREATE TABLE IF NOT EXISTS public.shipment_custody (
    shipment_id TEXT PRIMARY KEY,
    active_device_id TEXT NOT NULL,
    custody_state TEXT NOT NULL,
    updated_at BIGINT NOT NULL
);

-- 2. Index on active_device_id and custody_state for fast custody queries
CREATE INDEX IF NOT EXISTS idx_shipment_custody_device 
ON public.shipment_custody (active_device_id, custody_state);

-- 3. Grant table permissions to PostgREST roles (anon, authenticated, service_role)
GRANT ALL ON TABLE public.shipment_custody TO anon, authenticated, service_role;

-- 4. Enable Row Level Security (RLS) and grant permissive prototype policies
ALTER TABLE public.shipment_custody ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Allow public select on shipment_custody" ON public.shipment_custody;
CREATE POLICY "Allow public select on shipment_custody"
ON public.shipment_custody FOR SELECT
TO anon, authenticated, service_role
USING (true);

DROP POLICY IF EXISTS "Allow public insert on shipment_custody" ON public.shipment_custody;
CREATE POLICY "Allow public insert on shipment_custody"
ON public.shipment_custody FOR INSERT
TO anon, authenticated, service_role
WITH CHECK (true);

DROP POLICY IF EXISTS "Allow public update on shipment_custody" ON public.shipment_custody;
CREATE POLICY "Allow public update on shipment_custody"
ON public.shipment_custody FOR UPDATE
TO anon, authenticated, service_role
USING (true)
WITH CHECK (true);

-- 5. Reload PostgREST schema cache
NOTIFY pgrst, 'reload schema';
