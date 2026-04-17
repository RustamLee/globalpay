import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1/transfer';
const ACCOUNT_IDS = parseAccountIds(__ENV.ACCOUNT_IDS);

export const stressSuccessRate  = new Rate('stress_success_rate');
export const stressErrorRate    = new Rate('stress_error_rate');
export const stressSuccessCount = new Counter('stress_success_count');
export const stressErrorCount   = new Counter('stress_error_count');

export const options = {

    summaryTrendStats: ['p(95)', 'p(99)', 'avg', 'min', 'max'],
    scenarios: {
        transfer_stress: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 50 },
                { duration: '1m',  target: 50 },
                { duration: '30s', target: 0  },
            ],
            gracefulRampDown: '5s',
        },
    },
    thresholds: {
        'http_req_duration': ['p(95)<2000', 'p(99)<3000'],
        'http_req_failed':   ['rate<0.01'],
        'stress_success_rate': ['rate>0.99'],
    },
};

export default function () {
    const { fromId, toId } = pickAccountPair();
    const payload = JSON.stringify({
        fromId,
        toId,
        amount: randomAmount(),
        idempotencyKey: uuidv4(),
    });

    const params = {
        headers: { 'Content-Type': 'application/json' },
        tags: {
            test_type: 'transfer-stress',
            endpoint:  'transfer',
        },
    };

    const res = http.post(BASE_URL, payload, params);

    const isSuccess = res.status === 200;
    const isError   = res.status >= 400;

    stressSuccessRate.add(isSuccess);
    stressErrorRate.add(isError);

    if (isSuccess) stressSuccessCount.add(1);
    if (isError)   stressErrorCount.add(1);

    check(res, {
        'is status 200': (r) => r.status === 200,
    });

    sleep(0.1);
}

function parseAccountIds(raw) {
    const ids = (raw || '')
        .split(',')
        .map((id) => id.trim())
        .filter(Boolean);

    if (ids.length < 3) {
        throw new Error(
            'transfer-stress.js requires ACCOUNT_IDS with at least 3 comma-separated UUIDs for distributed stress testing'
        );
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

function pickAccountPair() {
    const fromIndex = ((__VU - 1) + __ITER) % ACCOUNT_IDS.length;
    const offset = 1 + (((__VU * 7) + __ITER) % (ACCOUNT_IDS.length - 1));
    const toIndex = (fromIndex + offset) % ACCOUNT_IDS.length;

    return {
        fromId: ACCOUNT_IDS[fromIndex],
        toId: ACCOUNT_IDS[toIndex],
    };
}

function randomAmount() {
    return (1 + Math.random() * 24).toFixed(2);
}


// 1. create 200 users
// 2. create 200 accounts
// 3. run by: ACCOUNT_IDS=$(PGPASSWORD=devpassword psql -h localhost -U devuser -d globalpay -Atc "SELECT string_agg(id::text, ',' ORDER BY id) FROM accounts WHERE id::text LIKE '40000000-0000-0000-0000-%';") K6_PROMETHEUS_RW_TREND_STATS="p(95),p(99)" k6 run --out experimental-prometheus-rw -e ACCOUNT_IDS="$ACCOUNT_IDS" -e BASE_URL="http://localhost:8080/api/v1/transfer" transfer-stress.js

