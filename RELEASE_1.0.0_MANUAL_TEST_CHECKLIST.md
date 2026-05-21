# Release 1.0.0 Manual Test Checklist

Verify before merging `performance-security-review` to `main`. Only items here require real services or production-like load — everything else is pinned by unit tests on this branch.

## Cache
- [ ] Memcached vs existing cluster — old keys readable (key format / value JSON shape backward-compat).

## Email outbound
- [ ] JavaSMTP ~1k concurrent sends — no thread starvation under `Dispatchers.IO` move.

## PubSub
- [ ] Redis Lettuce auto-reconnect — kill the network for ~30s, confirm subscription resumes without manual intervention.

## Notifications (FCM)
- [ ] Real FCM account, >800 tokens via `Semaphore(8)` parallel chunks — per-project QPS quota not exceeded.

## Voice agent
- [ ] OpenAI session back-pressure in prod — `Channel(64, DROP_OLDEST)` is acceptable; alert on observed drops.

## Twilio (SMS outbound)
- [ ] **TwilioSMS.send 5xx-after-accept** — watch for duplicate SMS. `send()` retries on 429+5xx without an Idempotency-Key; if duplicates appear in prod, narrow retry to 429-only (see commit history for the previously-reverted fix) or add an idempotency layer at the caller.

## Stripe
- [ ] Webhook construct/parse in staging — Stripe SDK call is unchanged but worth a smoke test.
- [ ] Caller layer dedups by Stripe event ID — no idempotency was added at this layer.

## AWS shared
- [ ] `AwsConnections.total` default lowered `Int.MAX_VALUE → 1000` — watch CloudWatch on the busiest server; tune `total` (it's a public `var`) if health flips to `DEGRADED`/`URGENT`/`ERROR` falsely.

## Module structure
- [ ] No production server depends on the removed `cache-dynamodb-kmp` module; otherwise migrate to `cache-dynamodb` (JVM-only) or another KMP cache.

---

## How to roll back

1. Revert merge: `git revert -m 1 <merge-sha> && git push origin main`
2. Single-module (preferred): `git log --oneline c6634ee0~1..HEAD -- <path>` then `git revert <sha>`
3. Re-tag prior release; redeploy; confirm `publishToMavenLocal` hashes match.
4. Mitigations without revert:
   - AWS saturation: override `AwsConnections.total` via settings.
   - FCM QPS: lower `Semaphore` permits via hotfix.
   - Twilio duplicates: re-apply the 429-only retry narrowing.
5. File issue with repro + logs; add regression test before re-merging.
