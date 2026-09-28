UPDATE audit_log SET created_at = to_char(created_at::timestamptz AT TIME ZONE 'UTC',
  'YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
UPDATE api_requests SET created_at = to_char(created_at::timestamptz AT TIME ZONE 'UTC',
  'YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
