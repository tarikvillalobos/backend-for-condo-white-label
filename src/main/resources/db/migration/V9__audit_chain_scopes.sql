ALTER TABLE audit_log ADD COLUMN chain_scope VARCHAR(160);
UPDATE audit_log SET chain_scope = 'brand' WHERE chain_scope IS NULL;
CREATE INDEX audit_chain_scope_order ON audit_log (tenant_id,brand_id,chain_scope,sequence);
