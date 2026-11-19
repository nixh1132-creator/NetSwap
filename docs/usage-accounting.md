# NetSwap usage accounting

## Changes in this batch
- Provider TCP and UDP endpoints continue to share one in-memory SessionTrafficQuota.
- SessionUsageReporter submits new byte deltas every five seconds.
- The authenticated record_netswap_usage RPC locks the request row, checks that auth.uid() is the provider (receiver_id), verifies approved status, caps usage at the grant, and completes the request at exhaustion.
- Reports are serialized, retried after transient failures, and split into at most 64 MiB increments.

## Security
- No service-role key is shipped to Android.
- Authenticated clients receive no direct used_bytes update permission.
- The RPC rejects unauthenticated users, the wrong participant, inactive requests, invalid increments, and oversized batches.

## Limitation
This is authenticated provider-reported accounting, not independent gateway metering. Android is still the source of the byte count. The local quota is the immediate per-packet limiter. A future gateway must independently measure and enforce traffic to provide anti-tamper accounting. A final delta may be lost if the app process exits while offline.

## Device verification
Test two physical Android devices with TCP and UDP traffic, wait for at least one five-second reporting interval, refresh request lists, verify used_bytes increases, test non-provider RPC rejection, quota completion, cancellation, and offline retry. A passing build is not a substitute for this test.