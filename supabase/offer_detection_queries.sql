-- Offer detection log queries for uber_logs
-- Assumes rows are written by RemoteLogger into:
--   uber_logs(device_id text, log_type text, data jsonb, created_at timestamptz)

-- 1. Recent offer detection events across all devices
select
  created_at,
  device_id,
  data->>'source' as source,
  data->>'stage' as stage,
  data->>'success' as success,
  data->>'class_name' as class_name,
  data->>'view_id' as view_id,
  data->>'match_type' as match_type,
  data->>'strong_marker' as strong_marker,
  data->>'state' as state
from uber_logs
where log_type = 'debug'
  and data->>'event_type' = 'offer_detection'
order by created_at desc
limit 200;

-- 2. Recent offer detection events for one device
-- replace DEVICE_ID_HERE
select
  created_at,
  data->>'source' as source,
  data->>'stage' as stage,
  data->>'success' as success,
  data->>'class_name' as class_name,
  data->>'view_id' as view_id,
  data->>'match_type' as match_type,
  data->>'root_count' as root_count
from uber_logs
where device_id = 'DEVICE_ID_HERE'
  and log_type = 'debug'
  and data->>'event_type' = 'offer_detection'
order by created_at desc
limit 200;

-- 3. Detection funnel by source/stage in the last 24 hours
select
  data->>'source' as source,
  data->>'stage' as stage,
  data->>'success' as success,
  count(*) as events
from uber_logs
where log_type = 'debug'
  and data->>'event_type' = 'offer_detection'
  and created_at >= now() - interval '24 hours'
group by 1, 2, 3
order by 1, 2, 3;

-- 4. Class gate matched but confirmation failed
select
  created_at,
  device_id,
  data->>'class_name' as class_name,
  data->>'source' as source,
  data->>'state' as state
from uber_logs
where log_type = 'debug'
  and data->>'event_type' = 'offer_detection'
  and data->>'stage' = 'class_gate_unconfirmed'
order by created_at desc
limit 100;

-- 5. Which markers are confirming offers most often
select
  coalesce(data->>'view_id', data->>'match_type', data->>'text') as detector,
  count(*) as hits
from uber_logs
where log_type = 'debug'
  and data->>'event_type' = 'offer_detection'
  and data->>'stage' in ('viewid_gate_confirmed', 'text_gate_confirmed', 'address_gate_confirmed')
group by 1
order by hits desc;

-- 6. Parse trigger count by source in the last 24 hours
select
  data->>'source' as source,
  count(*) as parses_started
from uber_logs
where log_type = 'debug'
  and data->>'event_type' = 'offer_detection'
  and data->>'stage' = 'trigger_parse'
  and created_at >= now() - interval '24 hours'
group by 1
order by parses_started desc;

-- 7. Offer detection failures (nothing found)
select
  created_at,
  device_id,
  data->>'source' as source,
  data->>'root_count' as root_count,
  data->>'state' as state
from uber_logs
where log_type = 'debug'
  and data->>'event_type' = 'offer_detection'
  and data->>'stage' = 'offer_window_not_found'
order by created_at desc
limit 200;

-- 8. Correlate parse results after trigger_parse for one device
-- Useful to inspect whether a detection source leads to successful parsing.
with detection as (
  select
    created_at,
    device_id,
    data->>'source' as source
  from uber_logs
  where device_id = 'DEVICE_ID_HERE'
    and log_type = 'debug'
    and data->>'event_type' = 'offer_detection'
    and data->>'stage' = 'trigger_parse'
),
parse_logs as (
  select
    created_at,
    device_id,
    data->>'success' as success,
    data->>'error_message' as error_message
  from uber_logs
  where device_id = 'DEVICE_ID_HERE'
    and log_type = 'parse'
)
select
  d.created_at as detection_at,
  d.source,
  p.created_at as parse_at,
  p.success as parse_success,
  p.error_message
from detection d
left join lateral (
  select *
  from parse_logs p
  where p.created_at >= d.created_at
    and p.created_at < d.created_at + interval '10 seconds'
  order by p.created_at asc
  limit 1
) p on true
order by d.created_at desc
limit 100;

-- 9. Recent parse successes with structured parser metadata
select
  created_at,
  device_id,
  data->>'success' as success,
  data->'offer'->>'pickup' as pickup,
  data->'offer'->>'dropoff' as dropoff,
  data->'offer'->>'parser_source' as parser_source,
  data->'offer'->>'pickup_view_id' as pickup_view_id,
  data->'offer'->>'dropoff_view_id' as dropoff_view_id,
  data->'offer'->>'pickup_validated' as pickup_validated,
  data->'offer'->>'dropoff_validated' as dropoff_validated,
  data->'offer'->>'parse_confidence' as parse_confidence
from uber_logs
where log_type = 'parse'
  and data->>'success' = 'true'
order by created_at desc
limit 200;

-- 10. Parse failures with structured failure context
select
  created_at,
  device_id,
  data->>'error_message' as error_message,
  data->>'error_code' as error_code,
  data->>'failure_stage' as failure_stage,
  data->>'retry_count' as retry_count,
  data->>'ui_summary_ids' as ui_summary_ids,
  data->>'ui_summary_addrs' as ui_summary_addrs,
  data->>'ui_summary_btns' as ui_summary_btns
from uber_logs
where log_type = 'parse'
  and data->>'success' = 'false'
order by created_at desc
limit 200;
