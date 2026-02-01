import http from 'k6/http';
import {check} from 'k6';
import {randomIntBetween} from 'https://jslib.k6.io/k6-utils/1.2.0/index.js';

const URL = __ENV.API_URL || 'http://host.docker.internal:8082/outbox/record';
const RATE = parseInt(__ENV.RATE || '200', 10);
const DURATION = __ENV.DURATION || '1m';

export const options = {
    discardResponseBodies: true,
    scenarios: {
        contacts: {
            executor: 'constant-arrival-rate',
            rate: RATE,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: 500
        },
    },
};

export default function () {
    const url = `${URL}/test-${randomIntBetween(0, 10000)}`;

    let response = http.post(url);
    check(response, {'Status ist 200': (r) => r.status === 200});
}
