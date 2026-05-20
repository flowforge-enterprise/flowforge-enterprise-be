import http from 'k6/http';
import { check, sleep } from 'k6';

// Tell k6 that 401 / 403 / 404 are acceptable responses for this test.
// This is useful because the root path may be protected by Spring Security.
http.setResponseCallback(
    http.expectedStatuses(
        { min: 200, max: 399 },
        401,
        403,
        404
    )
);

export const options = {
    stages: [
        { duration: '30s', target: 10 },
        { duration: '1m', target: 30 },
        { duration: '1m', target: 50 },
        { duration: '30s', target: 0 },
    ],
    thresholds: {
        http_req_failed: ['rate<0.20'],
        http_req_duration: ['p(95)<2000'],
    },
};

export default function () {
    const res = http.get('http://127.0.0.1:8081/');

    check(res, {
        'service responded': (r) =>
            r.status === 200 ||
            r.status === 401 ||
            r.status === 403 ||
            r.status === 404,
    });

    sleep(1);
}