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
  created_at VARCHAR(40) NOT NULL, status_code INTEGER NOT NULL, payload TEXT NOT NULL
);
CREATE INDEX requests_scope ON api_requests (tenant_id, brand_id, location_id, created_at, request_id);
CREATE TABLE audit_log (
  id VARCHAR(128) PRIMARY KEY, tenant_id VARCHAR(128) NOT NULL, brand_id VARCHAR(128) NOT NULL,
  location_id VARCHAR(128), request_id VARCHAR(128), actor_id VARCHAR(128),
  action VARCHAR(128) NOT NULL, target_type VARCHAR(64), target_id VARCHAR(128),
  created_at VARCHAR(40) NOT NULL, previous_hash VARCHAR(64), hash VARCHAR(64) NOT NULL, payload TEXT NOT NULL
);
CREATE INDEX audit_scope ON audit_log (tenant_id, brand_id, location_id, created_at, id);
CREATE INDEX audit_trace ON audit_log (request_id);
CREATE TABLE audit_changes (
  id VARCHAR(128) PRIMARY KEY, tenant_id VARCHAR(128) NOT NULL, brand_id VARCHAR(128),
  location_id VARCHAR(128), request_id VARCHAR(128), table_name VARCHAR(64) NOT NULL,
  row_id VARCHAR(128) NOT NULL, created_at VARCHAR(40) NOT NULL, payload TEXT NOT NULL
);
CREATE INDEX changes_scope ON audit_changes (tenant_id, brand_id, location_id, created_at, id);
CREATE INDEX changes_trace ON audit_changes (request_id);
CREATE TABLE v1_idempotency (
  id VARCHAR(64) PRIMARY KEY, fingerprint VARCHAR(64) NOT NULL,
