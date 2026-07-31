#!/usr/bin/env bash
#
# Push the split/* branches and open (or retarget) one PR per branch.
#
# The branches form a dependency tree, not a chain — four of them sit directly on
# version-1.3 and can be reviewed and merged in parallel:
#
#   version-1.3
#   ├─ split/01-build-toolchain
#   │  ├─ split/02-aws-http-clients        (needs 01's aws catalog aliases)
#   │  ├─ split/03-http-client-okhttp      (needs 01's ktor-client-okhttp alias)
#   │  └─ split/07-fcm-http-v1             (needs 01's ktor-client-mock alias)
#   ├─ split/04-files-redesign
#   ├─ split/05-db-update-restrictions
#   └─ split/06-db-table-definition
#      └─ split/08-expensive-test-filter   (needs 06's public getContextual)
#
#   ./scripts/push-split-prs.sh -n   # show the intended bases
#   ./scripts/push-split-prs.sh      # push, then create or retarget the PRs
#
# Re-runnable: pushes are force-with-lease, and a branch that already has a PR gets its
# base corrected via `gh pr edit` rather than a duplicate PR.
#
set -euo pipefail

DRY=false
[[ "${1:-}" == "-n" || "${1:-}" == "--dry-run" ]] && DRY=true

cd "$(git rev-parse --show-toplevel)"

BRANCH=(); BASE=(); TITLE=(); BODY=()
pr() { BRANCH+=("$1"); BASE+=("$2"); TITLE+=("$3"); BODY+=("$4"); }

# ---------------------------------------------------------------------------
# Parents must be listed before their children.
# ---------------------------------------------------------------------------

pr "split/01-build-toolchain" "version-1.3" \
   "build: Kotlin 2.4.10, slf4j-simple logging binding, catalog additions" \
   "Toolchain and build hygiene, no behavior change.

- Kotlin 2.3.21 -> 2.4.10, KSP 2.3.6 -> 2.3.10.
- Swap the test-time logging binding from logback-classic to slf4j-simple; kotlin-logging 8
  ships no binding of its own.
- Restrict the KSP-processor wiring to the per-target \`ksp*\` configurations, skipping the
  bare \`ksp\` configuration that newer KSP no longer accepts.
- Drop the unused guava dependency from cache-redis.
- Add four version-catalog aliases: aws url-connection/netty-nio, ktor client okhttp/mock.

The three PRs based on this one need those aliases; nothing else in the split does."

pr "split/02-aws-http-clients" "split/01-build-toolchain" \
   "aws-client: replace aws-crt-client with url-connection + netty-nio" \
   "Drops the AWS CRT client, whose native runtime added ~19MB to every artifact pulling in
an AWS SDK.

Service SDKs each transitively bundle both default HTTP clients, so those are excluded and
one shared pair is declared instead: url-connection-client for the sync path (no transitive
deps) and netty-nio-client for the async path, since Netty is already present in typical
deployments via the server engine.

Based on 01 only because it references the two new aws catalog aliases."

pr "split/03-http-client-okhttp" "split/01-build-toolchain" \
   "http-client: use the OkHttp engine on JVM for HTTP/2" \
   "Switches the shared JVM Ktor client from CIO to OkHttp, which negotiates h2 via ALPN and
multiplexes concurrent requests over few connections. Also raises OkHttp's dispatcher caps,
which default to 64 total / 5 per host — far too low once requests multiplex.

High-fanout callers benefit most, notably FCM push (PR 07). That is a performance pairing,
not a dependency: this change is entirely internal to HttpClientJvm and alters no API.

Based on 01 only because it references the new ktor-client-okhttp alias."

pr "split/04-files-redesign" "version-1.3" \
   "files: move operations onto PublicFileSystem, add ExternalFile/ExternalPath" \
   "The core of the files redesign. Independent of everything else in the split.

- File operations move off \`FileObject\` and onto the owning \`PublicFileSystem\`.
- \`ExternalFile\`/\`ExternalPath\` replace the old handle types.
- The hand-rolled \`FileSystemTracing\` expect/actual set is removed.
- Deletes the abandoned files-s3-kmp module, already commented out of settings.gradle.kts,
  and folds the S3 implementation back into a single \`S3PublicFileSystem\`.

Largest diff in the split, but one coherent change — the deletions are most of it."

pr "split/05-db-update-restrictions" "version-1.3" \
   "database: redesign UpdateRestrictions with per-field option lists" \
   "\`UpdateRestrictions\` becomes a list of options per field rather than a flat pair of
condition lists, so a field can be writable under several distinct conditions.

Adds a dedicated serializer for the new shape, plus \`GuaranteedAfterTest\` covering the
modification-implication logic it relies on.

Touches only database-shared sources; independent of the table-definition work in PR 06."

pr "split/06-db-table-definition" "version-1.3" \
   "database: introduce DatabaseTableDefinition and explicit null ordering" \
   "Table creation moves behind a \`DatabaseTableDefinition\` describing the table and its
indexes, replacing the ad-hoc per-driver signatures. \`SortPart\` gains explicit
nulls-first/nulls-last ordering.

All drivers are updated to the new contract: mongodb, postgres, sql, jsonfile, cassandra
and in-memory. database-sql picks up embedded postgres in tests because Postgres is the only
backend that sorts nulls last by default, making it the one that can actually guard the
ordering contract — H2 and SQLite cannot detect a regression here.

Also promotes \`SerializersModule.getContextual\` from internal to public, needed by the
mongodb driver here and by the ai module in PR 08."

pr "split/07-fcm-http-v1" "split/01-build-toolchain" \
   "notifications-fcm: replace Firebase Admin SDK with direct FCM HTTP v1" \
   "Drops the Firebase Admin SDK in favor of calling the FCM HTTP v1 API directly over the
shared http-client, removing a heavy dependency and its transitive Google stack.

Service-account JWT signing and the message payloads are now explicit types. The test suite
is rebuilt on ktor's MockEngine.

Based on 01 only because it references the new ktor-client-mock alias. It pairs naturally
with the HTTP/2 work in PR 03 — FCM now sends one request per device token — but does not
depend on it."

pr "split/08-expensive-test-filter" "split/06-db-table-definition" \
   "build: exclude live-service tests from normal runs; tighten AI tool schemas" \
   "Live-service suites — Ollama, LM Studio, keyed cloud providers — are slow, machine
dependent and sometimes billed. They move into an \`integration\` package that the normal
test run excludes; run them deliberately with \`-Pexpensive\`:

    ./gradlew :ai-ollama:test -Pexpensive --tests '*.integration.*'

Running a single suite from the IDE is unaffected. Includes the ai/ai-ollama test
reorganization that motivates the filter and the tool-schema fixes found while doing it.

Based on 06 because ai/toolSchema.kt switches to the database \`getContextual\` extension,
which 06 makes public."

N=${#BRANCH[@]}

TREE='```
version-1.3
├─ split/01-build-toolchain
│  ├─ split/02-aws-http-clients
│  ├─ split/03-http-client-okhttp
│  └─ split/07-fcm-http-v1
├─ split/04-files-redesign
├─ split/05-db-update-restrictions
└─ split/06-db-table-definition
   └─ split/08-expensive-test-filter
```'

# ---------------------------------------------------------------------------

command -v gh >/dev/null || { echo "gh CLI not found" >&2; exit 1; }

for ((i = 0; i < N; i++)); do
    git rev-parse --verify --quiet "${BRANCH[$i]}" >/dev/null \
        || { echo "missing branch: ${BRANCH[$i]}" >&2; exit 1; }
done

if $DRY; then
    for ((i = 0; i < N; i++)); do printf '%-32s -> %s\n' "${BRANCH[$i]}" "${BASE[$i]}"; done
    exit 0
fi

# Every branch must be on the remote before any PR references it as a base.
for b in "${BRANCH[@]}"; do
    echo "==> pushing $b"
    git push --force-with-lease --set-upstream origin "$b"
done

for ((i = 0; i < N; i++)); do
    branch="${BRANCH[$i]}"
    base="${BASE[$i]}"
    body="${BODY[$i]}

---

Part of a dependency tree splitting \`files-updaterestrictions-redesign\`. This diff is
against \`$base\`, so it shows only this change. Branches based on \`version-1.3\` are independent
and can merge in any order.

$TREE"

    if gh pr view "$branch" --json number >/dev/null 2>&1; then
        echo "==> retargeting existing PR: $branch -> $base"
        gh pr edit "$branch" --base "$base" --title "${TITLE[$i]}" --body "$body"
    else
        echo "==> creating PR: $branch -> $base"
        gh pr create --base "$base" --head "$branch" --title "${TITLE[$i]}" --body "$body"
    fi
done
