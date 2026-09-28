CREATE INDEX reservation_space_lookup ON app_records
  (tenant_id, location_id, (payload::jsonb ->> 'spaceId')) WHERE kind='v1_reservation';
CREATE INDEX reservation_member_space_lookup ON app_records
  (tenant_id, location_id, (payload::jsonb ->> 'membershipId'), (payload::jsonb ->> 'spaceId'))
  WHERE kind='v1_reservation';
CREATE INDEX space_block_lookup ON app_records
  (tenant_id, location_id, (payload::jsonb ->> 'spaceId')) WHERE kind='v1_space_block';
