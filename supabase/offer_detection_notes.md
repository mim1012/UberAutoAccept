# Offer Detection Logs

Remote offer-detection traces are stored in `uber_logs` with:

- `log_type = 'debug'`
- `data.event_type = 'offer_detection'`

Key fields in `data`:

- `source`: where the detection flow started
  `service_connected`, `watchdog`, `window_state_changed`, `window_state_class_gate`, `window_state_retry`, `window_content_changed`
- `stage`: which step was reached
  `startup_scan`, `watchdog_scan`, `window_state_received`, `class_gate_match`, `class_gate_unconfirmed`, `content_changed_received`, `viewid_gate_confirmed`, `text_gate_confirmed`, `address_gate_confirmed`, `offer_window_not_found`, `trigger_parse`
- `success`: whether that stage succeeded
- optional details:
  `class_name`, `view_id`, `strong_marker`, `match_type`, `package_name`, `root_count`, `state`

Parse logs are stored separately with:

- `log_type = 'parse'`
- `data.success`
- `data.offer.pickup`
- `data.offer.dropoff`
- `data.offer.parse_confidence`
- `data.offer.parser_source`
- `data.offer.pickup_view_id`
- `data.offer.dropoff_view_id`
- `data.offer.pickup_validated`
- `data.offer.dropoff_validated`
- `data.error_code`
- `data.failure_stage`
- `data.ui_summary_ids`
- `data.ui_summary_addrs`
- `data.ui_summary_btns`

Typical happy path:

1. `window_state_received`
2. `class_gate_match`
3. `viewid_gate_confirmed`
4. `trigger_parse`

Fallback paths:

- `watchdog_scan` -> `viewid_gate_confirmed` -> `trigger_parse`
- `content_changed_received` -> `address_gate_confirmed` -> `trigger_parse`
- `class_gate_match` -> `class_gate_unconfirmed`
- `offer_window_not_found`

Current parser sources:

- `uda_details`
- `card_viewid`
- `standard_offer`
- `leg_offer`
- `alt_dropoff`

Migration note:

- `uber_logs.data` is already `jsonb`, so no table-column migration is required for the new parse metadata.
- If query volume grows, apply [add_offer_parse_indexes.sql](/D:/Project/UberAutoAccept/supabase/add_offer_parse_indexes.sql:1) for expression indexes.

Use [offer_detection_queries.sql](/D:/Project/UberAutoAccept/supabase/offer_detection_queries.sql) for ready-made queries.

REST examples:

```bash
# Recent offer-detection logs
curl -s "$SUPABASE_URL/rest/v1/uber_logs?select=created_at,device_id,log_type,data&log_type=eq.debug&data->>event_type=eq.offer_detection&order=created_at.desc&limit=100" \
  -H "apikey: $SUPABASE_SERVICE_KEY" \
  -H "Authorization: Bearer $SUPABASE_SERVICE_KEY"

# One device only
curl -s "$SUPABASE_URL/rest/v1/uber_logs?select=created_at,device_id,log_type,data&device_id=eq.DEVICE_ID_HERE&log_type=eq.debug&data->>event_type=eq.offer_detection&order=created_at.desc&limit=100" \
  -H "apikey: $SUPABASE_SERVICE_KEY" \
  -H "Authorization: Bearer $SUPABASE_SERVICE_KEY"
```
