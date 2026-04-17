import argparse, csv, json, os, re, shutil, subprocess, sys, time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

STRONG_MARKERS = {
    'ub__upfront_offer_view_v2',
    'pulse_view',
    'dispatch_view',
    'dispatch_container',
    'offer_container',
    'upfront_offer_view_v2_workflow',
}
BLACKLIST_JOB_BOARD = {'driver_offers_job_board_content_container', 'driver_offers_job_board_toolbar'}
BLACKLIST_QUEUE = {'ub__driver_job_offers_pill_title', 'ub__driver_job_offers_pill_badge'}
PICKUP_IDS = {'uda_details_pickup_address_text_view', 'pick_up_address'}
DROPOFF_IDS = {'uda_details_dropoff_address_text_view', 'drop_off_address'}
ACCEPT_IDS = {
    'upfront_offer_configurable_details_accept_button',
    'uda_details_accept_button',
    'upfront_offer_configurable_details_auditable_accept_button',
}
OFFERISH_TOKENS = ('offer','dispatch','pickup','dropoff','accept','upfront','pulse','job_board','job_offers')
ADDRESS_RE = re.compile(r'시|구|동|로|길|역|터미널')
TRIP_DURATION_RE = re.compile(r'(?:\d+\s*시간\s*)?\d+\s*분\s*운행')
PICKUP_ETA_RE = re.compile(r'\d+\s*분\s*\([\d.]+\s*km\)\s*남음')
DIRECTION_RE = re.compile(r'[동서남북]{1,2}쪽')
TIME_RE = re.compile(r'\d+\s*분|\d+\s*min|ETA|예정|도착|남음|pickup|trip', re.I)
ACCEPT_RE = re.compile(r'콜 수락|수락|accept|확인', re.I)
EMPTY_RE = re.compile(r'지금은 요청이 없습니다|더 많은 요청이 들어오면 알려드리겠습니다|운행 리스트')
OFFER_TITLE_RE = re.compile(r'가맹 전용 콜|일반 콜|XL', re.I)


def looks_like_address(text):
    if not text:
        return False
    candidate = text.strip()
    if len(candidate) < 8 or candidate.endswith('쪽'):
        return False
    term_hits = len(re.findall(ADDRESS_RE, candidate))
    return term_hits >= 2 or ',' in candidate


def summarize_offer_texts(texts, resource_ids):
    normalized = [text.strip() for text in texts if text and text.strip()]
    if not normalized:
        return {
            'is_likely_offer': False,
            'reason': 'no_text_content',
            'title_text': '',
            'trip_duration_text': '',
            'pickup_eta_text': '',
            'pickup_address': '',
            'dropoff_address': '',
            'direction_text': '',
            'accept_text': '',
            'address_candidates': [],
            'blacklist_text_hit': '',
        }

    blacklist_hit = next((text for text in normalized if EMPTY_RE.search(text)), '')
    if blacklist_hit:
        return {
            'is_likely_offer': False,
            'reason': 'blacklist_text_present',
            'title_text': '',
            'trip_duration_text': '',
            'pickup_eta_text': '',
            'pickup_address': '',
            'dropoff_address': '',
            'direction_text': '',
            'accept_text': '',
            'address_candidates': [],
            'blacklist_text_hit': blacklist_hit,
        }

    title_text = next((text for text in normalized if OFFER_TITLE_RE.search(text)), '')
    trip_duration_text = next((text for text in normalized if TRIP_DURATION_RE.search(text)), '')
    pickup_eta_text = next((text for text in normalized if PICKUP_ETA_RE.search(text)), '')
    accept_text = next((text for text in normalized if ACCEPT_RE.search(text)), '')
    direction_text = next((text for text in normalized if DIRECTION_RE.fullmatch(text) or text.endswith('쪽')), '')
    address_candidates = [text for text in normalized if looks_like_address(text)]
    has_offerish_map_structure = any(
        rid.startswith('map_marker') or rid in {'rxmap', 'map'}
        for rid in resource_ids
    )
    is_likely_offer = bool(
        accept_text and
        trip_duration_text and
        pickup_eta_text and
        len(address_candidates) >= 2 and
        has_offerish_map_structure
    )

    return {
        'is_likely_offer': is_likely_offer,
        'reason': 'accept_trip_eta_and_two_addresses' if is_likely_offer else 'missing_offer_text_cluster',
        'title_text': title_text,
        'trip_duration_text': trip_duration_text,
        'pickup_eta_text': pickup_eta_text,
        'pickup_address': address_candidates[0] if len(address_candidates) > 0 else '',
        'dropoff_address': address_candidates[1] if len(address_candidates) > 1 else '',
        'direction_text': direction_text,
        'accept_text': accept_text,
        'address_candidates': address_candidates,
        'blacklist_text_hit': '',
    }


def now_utc_basic():
    return datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')

def now_iso_local():
    return datetime.now().strftime('%Y-%m-%dT%H:%M:%S')

def adb_cmd(device, *args, check=True):
    cmd = ['adb']
    if device:
        cmd += ['-s', device]
    cmd += list(args)
    proc = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')
    output = '\n'.join(x for x in [proc.stdout.strip(), proc.stderr.strip()] if x)
    if check and proc.returncode != 0:
        raise RuntimeError(f"adb failed: {' '.join(cmd)}\n{output}")
    return proc.returncode, output


def write_json(path, obj):
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding='utf-8')


def extract_attr(raw, name):
    return re.findall(fr'{re.escape(name)}="([^"]*)"', raw)


def parse_ui(xml_path: Path):
    raw = xml_path.read_text(encoding='utf-8', errors='replace')
    resource_ids = [(x.split(':id/')[-1]) for x in extract_attr(raw, 'resource-id') if x]
    classes = [(x.split('.')[-1]) for x in extract_attr(raw, 'class') if x]
    texts = [x for x in extract_attr(raw, 'text') if x.strip()]
    descs = [x for x in extract_attr(raw, 'content-desc') if x.strip()]
    all_text = texts + descs

    strong = sorted(STRONG_MARKERS.intersection(resource_ids))
    blacklist_job = sorted(BLACKLIST_JOB_BOARD.intersection(resource_ids))
    blacklist_queue = sorted(BLACKLIST_QUEUE.intersection(resource_ids))
    blacklist = blacklist_job + blacklist_queue
    offerish = sorted({rid for rid in resource_ids if any(tok in rid.lower() for tok in OFFERISH_TOKENS)})
    pickup_ids = sorted(PICKUP_IDS.intersection(resource_ids))
    dropoff_ids = sorted(DROPOFF_IDS.intersection(resource_ids))
    accept_ids = sorted(ACCEPT_IDS.intersection(resource_ids))
    accept_texts = sorted({t for t in all_text if ACCEPT_RE.search(t)})
    time_texts = sorted({t for t in all_text if TIME_RE.search(t)})
    addr_texts = [t for t in all_text if ADDRESS_RE.search(t)][:10]
    top_ids = [f'{k}:{v}' for k,v in Counter(resource_ids).most_common(12)]
    top_classes = [f'{k}:{v}' for k,v in Counter(classes).most_common(8)]
    text_cluster = summarize_offer_texts(all_text, resource_ids)

    sample_type = 'UNKNOWN'
    reason = 'candidate_without_confirmation'
    if strong or (pickup_ids and dropoff_ids and (accept_ids or accept_texts)):
        sample_type = 'REAL_OFFER'
        reason = 'strong_marker_or_pickup_dropoff_accept_cluster'
    elif text_cluster['is_likely_offer']:
        sample_type = 'REAL_OFFER'
        reason = text_cluster['reason']
    elif blacklist_job:
        sample_type = 'NON_OFFER_JOB_BOARD'
        reason = 'job_board_blacklist_ids_present'
    elif blacklist_queue:
        sample_type = 'NON_OFFER_QUEUE'
        reason = 'queue_pill_blacklist_ids_present'
    elif any(EMPTY_RE.search(t) for t in all_text):
        sample_type = 'NON_OFFER_EMPTY'
        reason = 'empty_state_text_present'

    fingerprint = '|'.join(strong + blacklist + pickup_ids + dropoff_ids + accept_ids + accept_texts + time_texts + top_ids)
    return {
        'sample_type': sample_type,
        'reason': reason,
        'strong_markers': strong,
        'blacklist_ids': blacklist,
        'offerish_ids': offerish,
        'pickup_ids': pickup_ids,
        'dropoff_ids': dropoff_ids,
        'accept_ids': accept_ids,
        'accept_texts': accept_texts,
        'time_texts': time_texts,
        'addr_texts': addr_texts,
        'text_cluster': text_cluster,
        'top_ids': top_ids,
        'top_classes': top_classes,
        'fingerprint': fingerprint,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--device', default='')
    ap.add_argument('--target-offers', type=int, default=5)
    ap.add_argument('--poll-seconds', type=int, default=10)
    ap.add_argument('--output-dir', default='')
    ap.add_argument('--max-minutes', type=int, default=0)
    args = ap.parse_args()

    outdir = Path(args.output_dir) if args.output_dir else Path('.omx/logs') / f'offer-monitor-{now_utc_basic()}'
    outdir.mkdir(parents=True, exist_ok=True)
    events_dir = outdir / 'events'
    events_dir.mkdir(exist_ok=True)
    raw_log = outdir / 'logcat-raw.txt'
    samples_csv = outdir / 'offer-samples.csv'
    status_json = outdir / 'status.json'

    for perm in ['android.permission.READ_PHONE_STATE','android.permission.READ_PHONE_NUMBERS','android.permission.POST_NOTIFICATIONS']:
        adb_cmd(args.device, 'shell','pm','grant','com.uber.autoaccept.debug', perm, check=False)
    adb_cmd(args.device, 'shell','settings','delete','secure','enabled_accessibility_services', check=False)
    adb_cmd(args.device, 'shell','settings','put','secure','enabled_accessibility_services','com.uber.autoaccept.debug/com.uber.autoaccept.service.UberAccessibilityService:com.teamviewer.quicksupport.addon.universal/com.teamviewer.quicksupport.addon.universal.TvAccessibilityService', check=False)
    adb_cmd(args.device, 'shell','settings','put','secure','accessibility_enabled','1', check=False)
    adb_cmd(args.device, 'shell','am','force-stop','com.uber.autoaccept', check=False)
    adb_cmd(args.device, 'shell','svc','power','stayon','true', check=False)
    adb_cmd(args.device, 'shell','wm','dismiss-keyguard', check=False)
    adb_cmd(args.device, 'logcat','-c', check=False)

    with raw_log.open('w', encoding='utf-8') as rawfh:
        cmd = ['adb'] + (['-s', args.device] if args.device else []) + ['logcat','-v','time','UAA_OFFER:I','UAA:I','UberAccessibilityService:I','UberOfferParser:I','*:S']
        logcat_proc = subprocess.Popen(cmd, stdout=rawfh, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')

        with samples_csv.open('w', newline='', encoding='utf-8') as fh:
            writer = csv.writer(fh)
            writer.writerow([
                'sample_id','sample_type','event_ts','strong_markers','offerish_ids','blacklist_ids',
                'accept_ids','accept_texts','pickup_ids','dropoff_ids','time_texts','addr_texts',
                'cluster_title','cluster_trip','cluster_eta','cluster_pickup','cluster_dropoff',
                'cluster_direction','top_ids','top_classes','classification_reason',
                'counts_toward_5','xml_path','screenshot_path'
            ])

            adb_cmd(args.device, 'shell','am','start','-n','com.uber.autoaccept.debug/com.uber.autoaccept.ui.MainActivity', check=False)
            time.sleep(2)
            adb_cmd(args.device, 'shell','input','keyevent','KEYCODE_HOME', check=False)
            time.sleep(1)
            adb_cmd(args.device, 'shell','am','start','-n','com.ubercab.driver/com.ubercab.carbon.core.CarbonActivity', check=False)

            seen = set()
            sample_id = 0
            real_offers = 0
            last_heartbeat = time.time()
            started_monotonic = time.time()
            timed_out = False

            while real_offers < args.target_offers:
                if args.max_minutes > 0 and (time.time() - started_monotonic) >= args.max_minutes * 60:
                    timed_out = True
                    break
                remote_xml = '/sdcard/uaa-monitor.xml'
                local_xml = outdir / 'latest.xml'
                if adb_cmd(args.device, 'shell','uiautomator','dump', remote_xml, check=False)[0] == 0 and adb_cmd(args.device, 'pull', remote_xml, str(local_xml), check=False)[0] == 0 and local_xml.exists():
                    snap = parse_ui(local_xml)
                    if snap['fingerprint']:
                        key = f"{snap['sample_type']}|{snap['fingerprint']}"
                        if key not in seen:
                            seen.add(key)
                            sample_id += 1
                            sample_dir = events_dir / f'sample-{sample_id:03d}'
                            sample_dir.mkdir(exist_ok=True)
                            xml_copy = sample_dir / 'ui.xml'
                            shutil.copy2(local_xml, xml_copy)
                            screen_copy = sample_dir / 'screen.png'
                            adb_cmd(args.device, 'shell','screencap','-p','/sdcard/uaa-monitor.png', check=False)
                            if adb_cmd(args.device, 'pull','/sdcard/uaa-monitor.png', str(screen_copy), check=False)[0] != 0:
                                screen_copy = Path('')
                            counts = 'yes' if snap['sample_type'] == 'REAL_OFFER' else 'no'
                            if counts == 'yes':
                                real_offers += 1
                            writer.writerow([
                                sample_id,
                                snap['sample_type'],
                                now_iso_local(),
                                ';'.join(snap['strong_markers']),
                                ';'.join(snap['offerish_ids']),
                                ';'.join(snap['blacklist_ids']),
                                ';'.join(snap['accept_ids']),
                                ';'.join(snap['accept_texts']),
                                ';'.join(snap['pickup_ids']),
                                ';'.join(snap['dropoff_ids']),
                                ';'.join(snap['time_texts']),
                                ';'.join(snap['addr_texts']),
                                snap['text_cluster']['title_text'],
                                snap['text_cluster']['trip_duration_text'],
                                snap['text_cluster']['pickup_eta_text'],
                                snap['text_cluster']['pickup_address'],
                                snap['text_cluster']['dropoff_address'],
                                snap['text_cluster']['direction_text'],
                                ';'.join(snap['top_ids']),
                                ';'.join(snap['top_classes']),
                                snap['reason'],
                                counts,
                                str(xml_copy),
                                str(screen_copy) if screen_copy else '',
                            ])
                            fh.flush()

                write_json(status_json, {
                    'started_at_utc': outdir.name.replace('offer-monitor-',''),
                    'device': args.device,
                    'target_offers': args.target_offers,
                    'max_minutes': args.max_minutes,
                    'real_offer_count': real_offers,
                    'samples_recorded': sample_id,
                    'last_poll_utc': datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
                    'raw_log_path': str(raw_log.resolve()),
                    'samples_csv_path': str(samples_csv.resolve()),
                    'logcat_pid': logcat_proc.pid,
                    'monitoring': real_offers < args.target_offers and not timed_out,
                    'timed_out': timed_out,
                })

                if time.time() - last_heartbeat >= 300:
                    adb_cmd(args.device, 'shell','am','start','-n','com.ubercab.driver/com.ubercab.carbon.core.CarbonActivity', check=False)
                    last_heartbeat = time.time()
                time.sleep(args.poll_seconds)

    if logcat_proc.poll() is None:
        logcat_proc.terminate()

    final_status = {
        'started_at_utc': outdir.name.replace('offer-monitor-',''),
        'device': args.device,
        'target_offers': args.target_offers,
        'max_minutes': args.max_minutes,
        'real_offer_count': real_offers,
        'samples_recorded': sample_id,
        'completed_at_utc': datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
        'raw_log_path': str(raw_log.resolve()),
        'samples_csv_path': str(samples_csv.resolve()),
        'logcat_pid': logcat_proc.pid,
        'monitoring': False,
        'timed_out': timed_out,
        'completion_reason': 'target_reached' if real_offers >= args.target_offers else ('timeout' if timed_out else 'stopped'),
    }
    write_json(status_json, final_status)

if __name__ == '__main__':
    main()
