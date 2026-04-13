-- Optional indexes for parse / offer-detection analytics on uber_logs.data jsonb.
-- No table-column migration is required because uber_logs.data is already jsonb.

create index if not exists idx_uber_logs_parse_success
  on uber_logs ((data->>'success'))
  where log_type = 'parse';

create index if not exists idx_uber_logs_parse_parser_source
  on uber_logs ((data->'offer'->>'parser_source'))
  where log_type = 'parse';

create index if not exists idx_uber_logs_parse_pickup_view_id
  on uber_logs ((data->'offer'->>'pickup_view_id'))
  where log_type = 'parse';

create index if not exists idx_uber_logs_parse_dropoff_view_id
  on uber_logs ((data->'offer'->>'dropoff_view_id'))
  where log_type = 'parse';

create index if not exists idx_uber_logs_offer_detection_stage
  on uber_logs ((data->>'stage'))
  where log_type = 'debug' and data->>'event_type' = 'offer_detection';
