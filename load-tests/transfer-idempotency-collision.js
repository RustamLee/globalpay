import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1/transfer';
const ACCOUNT_IDS = parseAccountIds(__ENV.ACCOUNT_IDS);
const HOT_KEY_POOL_SIZE = Number(__ENV.HOT_KEY_POOL_SIZE || 6);
const HOT_KEY_RATIO     = Number(__ENV.HOT_KEY_RATIO     || 0.95);
const SLEEP_SECONDS     = Number(__ENV.SLEEP_SECONDS     || 0.10);
const STAGE_1_DURATION  = __ENV.STAGE_1_DURATION || '15s';
const STAGE_1_TARGET    = Number(__ENV.STAGE_1_TARGET || 15);
const STAGE_2_DURATION  = __ENV.STAGE_2_DURATION || '30s';
const STAGE_2_TARGET    = Number(__ENV.STAGE_2_TARGET || 30);
const STAGE_3_DURATION  = __ENV.STAGE_3_DURATION || '45s';
const STAGE_3_TARGET    = Number(__ENV.STAGE_3_TARGET || 50);
const STAGE_4_DURATION  = __ENV.STAGE_4_DURATION || '15s';
const STAGE_4_TARGET    = Number(__ENV.STAGE_4_TARGET || 0);

// Each hot key always replays the same transfer payload.
const HOT_CASES = buildHotCases(HOT_KEY_POOL_SIZE, ACCOUNT_IDS);

export const idempotencyCollisionRate  = new Rate('idempotency_collision_rate');
export const idempotencySuccessRate    = new Rate('idempotency_success_rate');
export const idempotencyHotSuccessRate = new Rate('idempotency_hot_success_rate');
export const idempotencyOtherRate      = new Rate('idempotency_other_rate');
export const idempotencyCollisionCount = new Counter('idempotency_collision_count');
export const idempotencySuccessCount   = new Counter('idempotency_success_count');
export const idempotencyOtherCount     = new Counter('idempotency_other_count');
export const status200Count            = new Counter('idempotency_status_200_count');
export const status409Count            = new Counter('idempotency_status_409_count');
export const status503Count            = new Counter('idempotency_status_503_count');
export const status5xxCount            = new Counter('idempotency_status_5xx_count');

export const options = {
    scenarios: {
        idempotency_collision: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: STAGE_1_DURATION, target: STAGE_1_TARGET },
                { duration: STAGE_2_DURATION, target: STAGE_2_TARGET },
                { duration: STAGE_3_DURATION, target: STAGE_3_TARGET },
                { duration: STAGE_4_DURATION, target: STAGE_4_TARGET },
            ],
            gracefulRampDown: '5s',
        },
    },
    thresholds: {
        'http_req_failed': ['rate<0.01'],
        'http_req_duration': ['p(95)<3000'],
        // Unique writes should be a small but non-zero portion when HOT_KEY_RATIO is high.
        'idempotency_success_rate': ['rate>0.01', 'rate<0.20'],
        // Most requests in this mode should be replay hits.
        'idempotency_collision_rate': ['rate>0.70'],
        // Hot-key requests should almost always be replay hits (X-Idempotency-Hit=true).
        'idempotency_hot_success_rate': ['rate>0.95'],
        'idempotency_other_rate': ['rate<0.01'],
    },
};

export default function () {

    const useHotKey = Math.random() < HOT_KEY_RATIO;
    const hotCase = useHotKey ? HOT_CASES[Math.floor(Math.random() * HOT_CASES.length)] : null;
    const pair = hotCase || pickUniquePair();
    const idempotencyKey = hotCase ? hotCase.idempotencyKey : uuidv4();

    const payload = JSON.stringify({
        fromId: pair.fromId,
        toId:   pair.toId,
        amount: '1.00',
        idempotencyKey,
    });

    const params = {
        headers: { 'Content-Type': 'application/json' },
        tags: {
            test_type: 'idempotency-collision',
            endpoint:  'transfer',
            key_type:  useHotKey ? 'hot' : 'unique',
        },
    };

    const res = http.post(BASE_URL, payload, params);

    if (res.status === 200) status200Count.add(1);
    if (res.status === 409) status409Count.add(1);
    if (res.status === 503) status503Count.add(1);
    if (res.status >= 500) status5xxCount.add(1);

    const idempotencyHit = parseIdempotencyHit(res.headers);
    const isSuccess   = res.status === 200;
    const isUnique    = isSuccess && idempotencyHit === false;
    const isHit       = isSuccess && idempotencyHit === true;
    const isOther     = !isSuccess || idempotencyHit === null;

    idempotencySuccessRate.add(isUnique);
    idempotencyCollisionRate.add(isHit);
    if (useHotKey) {
        idempotencyHotSuccessRate.add(isHit);
    }
    idempotencyOtherRate.add(isOther);

    if (isUnique) idempotencySuccessCount.add(1);
    if (isHit) idempotencyCollisionCount.add(1);
    if (isOther) idempotencyOtherCount.add(1);

    check(res, {
        'status is 200': (r) => r.status === 200,
    });

    sleep(SLEEP_SECONDS);
}

function parseAccountIds(raw) {
    const ids = (raw || '')
        .split(',')
        .map((id) => id.trim())
        .filter(Boolean);

    if (ids.length < 6) {
        throw new Error('transfer-idempotency-collision.js requires ACCOUNT_IDS with at least 6 comma-separated UUIDs');
    }

    const invalidId = ids.find((id) => !isUuid(id));
    if (invalidId) {
        throw new Error(`Invalid account UUID in ACCOUNT_IDS: ${invalidId}`);
    }

    return ids;
}

function isUuid(value) {
    return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}

function buildHotCases(poolSize, accountIds) {
    const size = Math.max(3, Math.min(poolSize, accountIds.length));
    const hotCases = [];

    for (let i = 0; i < size; i += 1) {
        const fromIndex = i % accountIds.length;
        const toIndex = (i + 1 + (i % (accountIds.length - 1))) % accountIds.length;
        hotCases.push({
            idempotencyKey: uuidv4(),
            fromId: accountIds[fromIndex],
            toId: accountIds[toIndex],
        });
    }

    return hotCases;
}

function pickUniquePair() {
    const fromIndex = ((__VU - 1) + __ITER) % ACCOUNT_IDS.length;
    const toIndex = (fromIndex + 1 + (((__VU * 3) + __ITER) % (ACCOUNT_IDS.length - 1))) % ACCOUNT_IDS.length;
    return {
        fromId: ACCOUNT_IDS[fromIndex],
        toId: ACCOUNT_IDS[toIndex],
    };
}

function parseIdempotencyHit(headers) {
    const raw = headers['X-Idempotency-Hit'] ?? headers['x-idempotency-hit'];
    const value = Array.isArray(raw) ? raw[0] : raw;
    if (value === undefined || value === null) {
        return null;
    }

    const normalized = String(value).trim().toLowerCase();
    if (normalized === 'true') {
        return true;
    }
    if (normalized === 'false') {
        return false;
    }

    return null;
}

// 1. create 6 users
// 2. create 6 accounts
// 3. Run by:
// K6_PROMETHEUS_RW_TREND_STATS="p(95),p(99)" \
// k6 run --out experimental-prometheus-rw \
//   -e ACCOUNT_IDS="40000000-0000-0000-0000-000000000001,40000000-0000-0000-0000-000000000002,40000000-0000-0000-0000-000000000003,40000000-0000-0000-0000-000000000004,40000000-0000-0000-0000-000000000005,40000000-0000-0000-0000-000000000006" \
//   -e HOT_KEY_POOL_SIZE=6 \
//   -e HOT_KEY_RATIO=0.95 \
//   -e SLEEP_SECONDS=0.10 \
//   -e STAGE_1_TARGET=15 \
//   -e STAGE_2_TARGET=30 \
//   -e STAGE_3_TARGET=50 \
//   transfer-idempotency-collision.js