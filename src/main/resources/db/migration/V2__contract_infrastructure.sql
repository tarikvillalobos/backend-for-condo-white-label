CREATE TABLE v1_scope_locks (scope_id VARCHAR(256) PRIMARY KEY);
CREATE TABLE v1_record_versions (
  id VARCHAR(128) NOT NULL, version INTEGER NOT NULL,
  kind VARCHAR(64) NOT NULL, tenant_id VARCHAR(128) NOT NULL,
  location_id VARCHAR(128), owner_id VARCHAR(128), brand_id VARCHAR(128),
  payload TEXT NOT NULL, created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL, valid_from BIGINT NOT NULL, valid_to BIGINT,
  deleted BOOLEAN NOT NULL DEFAULT FALSE, PRIMARY KEY (id, version)
);
CREATE INDEX v1_versions_scope ON v1_record_versions (tenant_id, brand_id, kind, location_id, valid_from, id);
CREATE INDEX v1_versions_owner ON v1_record_versions (tenant_id, brand_id, kind, owner_id, valid_from, id);
CREATE TABLE v1_snapshots (
  id VARCHAR(128) PRIMARY KEY, tenant_id VARCHAR(128) NOT NULL,
  brand_id VARCHAR(128) NOT NULL, fingerprint VARCHAR(64) NOT NULL,
  snapshot_at BIGINT NOT NULL, expires_at BIGINT NOT NULL
);
CREATE INDEX v1_snapshots_expiry ON v1_snapshots (expires_at);
CREATE TABLE api_requests (
  request_id VARCHAR(128) PRIMARY KEY, tenant_id VARCHAR(128), brand_id VARCHAR(128),
  location_id VARCHAR(128), actor_id VARCHAR(128), operation_id VARCHAR(128),
