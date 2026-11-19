/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

/*
 * k6 load test for POST /api/v1/certificate/ocsp.
 *
 * Prepare a DER request and a token, then run from the repository root:
 *
 *   openssl ocsp -issuer ca.pem -cert ocsp-test.pem -reqout load-test/req.der
 *   k6 run \
 *     -e BASE_URL=https://localhost:8090 \
 *     -e TOKEN="$TOKEN" \
 *     -e CLIENT_CERT=client.crt -e CLIENT_KEY=client.key \
 *     -e REQUEST=load-test/req.der \
 *     -e RATE=200 -e DURATION=5m \
 *     load-test/ocsp.js
 *
 * Add -e INSECURE=true when the server certificate is not trusted by k6, e.g. a local self-signed one.
 * The run fails if p99 latency exceeds P99_MS (default 100 ms) or any request fails.
 * Nonces are not used, so the same request body can be replayed.
 */
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'https://localhost:8090';
const TOKEN = __ENV.TOKEN;
const RATE = Number(__ENV.RATE || 100);
const DURATION = __ENV.DURATION || '2m';
const P99_MS = Number(__ENV.P99_MS || 100);
const BODY = open(__ENV.REQUEST || 'load-test/req.der', 'b');

export const options = {
    insecureSkipTLSVerify: __ENV.INSECURE === 'true',
    tlsAuth: [
        {
            cert: open(__ENV.CLIENT_CERT || 'client.crt'),
            key: open(__ENV.CLIENT_KEY || 'client.key'),
        },
    ],
    scenarios: {
        ocsp: {
            executor: 'constant-arrival-rate',
            rate: RATE,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: Math.max(10, Math.ceil(RATE / 10)),
            maxVUs: Math.max(50, RATE),
        },
    },
    thresholds: {
        http_req_duration: [`p(99)<${P99_MS}`],
        http_req_failed: ['rate==0'],
        checks: ['rate==1'],
    },
};

export default function () {
    const res = http.post(`${BASE_URL}/api/v1/certificate/ocsp`, BODY, {
        headers: {
            'Content-Type': 'application/ocsp-request',
            Authorization: `Bearer ${TOKEN}`,
        },
        responseType: 'binary',
    });
    check(res, {
        'status is 200': (r) => r.status === 200,
        'is OCSP response': (r) => (r.headers['Content-Type'] || '').startsWith('application/ocsp-response'),
        // OCSPResponse ::= SEQUENCE { responseStatus ENUMERATED ... }; byte after the ENUMERATED tag/length is the status
        'OCSP status successful': (r) => successful(new Uint8Array(r.body)),
    });
}

function successful(der) {
    // Skip the outer SEQUENCE header (short or long form length)
    let i = 1;
    i += der[i] & 0x80 ? 1 + (der[i] & 0x7f) : 1;
    return der[i] === 0x0a && der[i + 1] === 0x01 && der[i + 2] === 0x00;
}
