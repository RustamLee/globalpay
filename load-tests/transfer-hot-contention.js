import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = 'http://localhost:8080/api/v1/transfer';


const HOT_FROM_ID = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const HOT_TO_ID = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';

export const successRate = new Rate('hot_account_success_rate');
export const conflictRate = new Rate('hot_account_conflict_rate');
export const lockTimeoutRate = new Rate('hot_account_lock_timeout_rate');
export const successCount = new Counter('hot_account_success_count');
export const conflictCount = new Counter('hot_account_conflict_count');
export const lockTimeoutCount = new Counter('hot_account_lock_timeout_count');

export const options = {
    scenarios: {
        hot_account_contention: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '20s', target: 23 },
                { duration: '30s', target: 35 },
                { duration: '40s', target: 35 },
                { duration: '90s', target: 35 },
                { duration: '30s', target: 0 },
            ],
            gracefulRampDown: '5s',
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<2000'],
        hot_account_success_rate: ['rate>0'],
        http_req_failed: ['rate<0.95'],
    },
};

export default function () {
    const payload = JSON.stringify({
        fromId: HOT_FROM_ID,
        toId: HOT_TO_ID,
        amount: '1.00',
        idempotencyKey: uuidv4(),
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
        },
        tags: {
            test_type: 'hot-account-contention',
            endpoint: 'transfer',
        },
    };

    const res = http.post(BASE_URL, payload, params);

    const isSuccess = res.status === 200;
    const isConflict = res.status === 409;
    const isLockTimeout = res.status === 503;

    successRate.add(isSuccess);
    conflictRate.add(isConflict);
    lockTimeoutRate.add(isLockTimeout);

    if (isSuccess) successCount.add(1);
    if (isConflict) conflictCount.add(1);
    if (isLockTimeout) lockTimeoutCount.add(1);

    check(res, {
        'status is 200 or 409 or 503': (r) =>
            r.status === 200 || r.status === 409 || r.status === 503,
    });

    sleep(0.01);
}

// 1. create 2 users
// 2. create 2 accounts
// 3. Run by: K6_PROMETHEUS_RW_TREND_STATS="p(95),p(99)" k6 run --out experimental-prometheus-rw transfer-hot-contention.js

