#!/usr/bin/env bash
#
# Split the files-updaterestrictions-redesign branch into a stack of reviewable PRs.
#
# The branch is one 99-file diff made of ~8 unrelated concerns. This rebuilds it as a
# tree of branches off version-1.3, each holding exactly one concern, then opens a PR per
# branch with `--base` pointing at the branch below it. Reviewers read one topic at a
# time; merging bottom-up keeps every diff honest.
#
# The split is by file path, taken from the final tree — not by cherry-picking the
# original commits, since the largest commit mixes six concerns. Every changed path is
# checked to belong to exactly one bucket before anything is created, so nothing is
# silently dropped or duplicated.
#
# Creating the stack and publishing it are separate phases. If the branches already
# exist, a re-run verifies each still holds exactly its bucket and then publishes;
# only --force rebuilds from scratch. So --pr is safe to run against a stack you
# already built and inspected.
#
#   ./scripts/split-branch-prs.sh --dry-run          # show the buckets and their sizes
#   ./scripts/split-branch-prs.sh                    # create (or verify) the branch stack
#   ./scripts/split-branch-prs.sh --build            # ...and compile-check each step
#   ./scripts/split-branch-prs.sh --pr               # ...and push + open stacked PRs
#   ./scripts/split-branch-prs.sh --force            # discard and rebuild the stack
#
set -euo pipefail

BASE="${BASE:-version-1.3}"
SOURCE="${SOURCE:-files-updaterestrictions-redesign}"
PREFIX="${PREFIX:-split/}"

DRY_RUN=false
DO_BUILD=false
DO_PUSH=false
DO_PR=false
FORCE=false
ONLY=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) DRY_RUN=true ;;
        --build)   DO_BUILD=true ;;
        --push)    DO_PUSH=true ;;
        --pr)      DO_PR=true; DO_PUSH=true ;;
        --force)   FORCE=true ;;
        --only)    ONLY="$2"; shift ;;
        --base)    BASE="$2"; shift ;;
        --source)  SOURCE="$2"; shift ;;
        --prefix)  PREFIX="$2"; shift ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
    shift
done

cd "$(git rev-parse --show-toplevel)"

# ---------------------------------------------------------------------------
# Bucket definitions. Parallel arrays (bash 3.2 on macOS has no assoc arrays).
# Order is the stack order: each branch is based on the one before it, so a
# bucket may depend on anything above it in this list but nothing below.
# ---------------------------------------------------------------------------

SLUG=(); TITLE=(); DESC=(); PATHS=(); VERIFY=()

bucket() { SLUG+=("$1"); TITLE+=("$2"); DESC+=("$3"); PATHS+=("$4"); VERIFY+=("$5"); }

bucket "01-build-toolchain" \
    "build: Kotlin 2.4.10, slf4j-simple logging binding, catalog additions" \
    "Toolchain and build hygiene, no behavior change:
- Kotlin 2.3.21 -> 2.4.10 and KSP 2.3.6 -> 2.3.10.
- Swap the test-time logging binding from logback-classic to slf4j-simple across
  modules; kotlin-logging 8 ships no binding of its own.
- Restrict the KSP-processor wiring to the per-target ksp* configurations, skipping
  the bare \`ksp\` configuration that newer KSP no longer accepts.
- Drop the unused guava dependency from cache-redis.
- Add catalog entries used by later PRs in this stack (aws netty-nio / url-connection,
  ktor client mock / okhttp). Catalog entries are inert until a module references them." \
    "gradle/libs.versions.toml
     basis/build.gradle.kts
     cache-dynamodb/build.gradle.kts
     cache-redis/build.gradle.kts
     database/build.gradle.kts
     database-shared/build.gradle.kts
     database-test/build.gradle.kts
     email/build.gradle.kts
     email-inbound-imap/build.gradle.kts
     email-inbound-ses/build.gradle.kts
     email-javasmtp/build.gradle.kts
     phonecall-twilio/build.gradle.kts
     pubsub-aws/build.gradle.kts
     pubsub-redis/build.gradle.kts
     sms-inbound-twilio/build.gradle.kts
     speech-elevenlabs/build.gradle.kts
     speech-local/build.gradle.kts
     speech-openai/build.gradle.kts
     subscription-payments-stripe/build.gradle.kts
     test/build.gradle.kts
     voiceagent-openai/build.gradle.kts
     voiceagent-phonecall/build.gradle.kts" \
    "./gradlew assemble"

bucket "02-aws-http-clients" \
    "aws-client: replace aws-crt-client with url-connection + netty-nio" \
    "Drops the AWS CRT client, whose native runtime added ~19MB to every artifact that
pulls in an AWS SDK. Service SDKs each bundle both default HTTP clients transitively;
those are excluded and one shared pair is declared instead — url-connection-client for
the sync path (no transitive deps) and netty-nio-client for the async path." \
    "aws-client/build.gradle.kts
     aws-client/src/main/kotlin/com/lightningkite/services/aws/AwsConnections.kt
     files-s3/build.gradle.kts" \
    "./gradlew :aws-client:build :files-s3:assemble"

bucket "03-http-client-okhttp" \
    "http-client: use the OkHttp engine on JVM for HTTP/2" \
    "Switches the shared JVM Ktor client from CIO to OkHttp, which supports HTTP/2
multiplexing. High-fanout callers (notably FCM push, rewritten later in this stack)
and general connection reuse both benefit." \
    "http-client/build.gradle.kts
     http-client/src/jvmMain/kotlin/com/lightningkite/services/http/HttpClientJvm.kt" \
    "./gradlew :http-client:build"

bucket "04-files-redesign" \
    "files: move operations onto PublicFileSystem, add ExternalFile/ExternalPath" \
    "The core of the files redesign. File operations move off FileObject and onto the
owning PublicFileSystem; ExternalFile/ExternalPath replace the old handle types; the
hand-rolled FileSystemTracing expect/actual set is removed. Deletes the abandoned
files-s3-kmp module (already commented out of settings.gradle.kts) and folds the S3
implementation back into a single S3PublicFileSystem." \
    "files/
     files-s3/src/
     files-s3-kmp/
     files-test/
     settings.gradle.kts
     docs/files-s3-module.md" \
    "./gradlew :files:build :files-s3:build :files-test:build"

bucket "05-db-update-restrictions" \
    "database: redesign UpdateRestrictions with per-field option lists" \
    "UpdateRestrictions becomes a list of options per field rather than a flat pair of
condition lists, so a field can be writable under several distinct conditions. Adds a
dedicated serializer for the new shape and the GuaranteedAfter test coverage that
pins the modification-implication logic it relies on." \
    "database-shared/src/commonMain/kotlin/com/lightningkite/services/database/UpdateRestrictions.kt
     database-shared/src/commonMain/kotlin/com/lightningkite/services/database/UpdateRestrictionsSerializer.kt
     database-shared/src/commonMain/kotlin/com/lightningkite/services/database/Mask.kt
     database-shared/src/commonMain/kotlin/com/lightningkite/services/database/ModelPermissions.kt
     database-shared/src/commonMain/kotlin/com/lightningkite/services/database/SerializationRegistry.kt
     database-shared/src/commonTest/kotlin/com/lightningkite/services/database/GuaranteedAfterTest.kt
     database-shared/src/commonTest/kotlin/com/lightningkite/services/database/UpdateRestrictionsTest.kt" \
    "./gradlew :database-shared:build"

bucket "06-db-table-definition" \
    "database: introduce DatabaseTableDefinition and explicit null ordering" \
    "Table creation moves behind a DatabaseTableDefinition describing the table and its
indexes, replacing the ad-hoc per-driver signatures, and SortPart gains explicit
nulls-first/nulls-last ordering. All drivers (mongodb, postgres, sql, jsonfile,
cassandra, in-memory) are updated to the new contract. database-sql picks up embedded
postgres in tests because Postgres is the only backend that sorts nulls last by
default, so it is the one that can actually guard the ordering contract." \
    "database/src/
     database-shared/src/commonMain/kotlin/com/lightningkite/services/database/SortPart.kt
     database-shared/src/commonMain/kotlin/com/lightningkite/services/database/SerializationHelpers.kt
     database-cassandra/
     database-jsonfile/
     database-mongodb/
     database-postgres/
     database-sql/
     database-test/src/" \
    "./gradlew :database:build :database-sql:build :database-jsonfile:build :database-test:build :database-mongodb:assemble :database-postgres:assemble"

bucket "07-fcm-http-v1" \
    "notifications-fcm: replace Firebase Admin SDK with direct FCM HTTP v1" \
    "Drops the Firebase Admin SDK in favor of calling the FCM HTTP v1 API directly over
the shared http-client, removing a heavy dependency and its transitive Google stack.
Service-account JWT signing and the message payloads are now explicit types, and the
test suite is rebuilt on ktor's MockEngine instead of the deleted chunk-failure test." \
    "notifications-fcm/" \
    "./gradlew :notifications-fcm:build"

bucket "08-expensive-test-filter" \
    "build: exclude live-service tests from normal runs; tighten AI tool schemas" \
    "Live-service suites (Ollama, LM Studio, keyed cloud providers) are slow, machine
dependent and sometimes billed. They move into an \`integration\` package that the
normal test run excludes; run them deliberately with \`-Pexpensive\`. Includes the
ai/ai-ollama test reorganization that motivates the filter and the tool-schema fixes
found while doing it." \
    "build.gradle.kts
     ai/
     ai-ollama/" \
    "./gradlew :ai:build :ai-ollama:build"

N=${#SLUG[@]}

# Real dependencies between buckets, so PRs can merge in parallel instead of in one
# 8-deep chain. Anything not listed here is independent and sits directly on $BASE.
#
#   02, 03, 07 -> 01   they reference version-catalog aliases 01 adds
#                      (aws-urlConnectionClient/nettyNioClient, ktor-client-okhttp,
#                       ktor-client-mock). Nothing resolves without them.
#   08         -> 06   ai/toolSchema.kt switches to the database `getContextual`
#                      extension, which 06 promotes from internal to public. A
#                      different module cannot see it otherwise.
#
# Deliberately NOT dependencies, each checked rather than assumed:
#   04 -> 02   no S3 source touches the CRT/HTTP-client classes 02 swaps.
#   06 -> 01   the postgres/embedded-postgres aliases 06 uses already exist on main.
#   06 -> 05   nothing in the db core or drivers references UpdateRestrictions.
#   07 -> 03   the OkHttp swap is internal to HttpClientJvm; no API change. The two
#              are a performance pairing (HTTP/2 fanout), not a compile dependency.
parent_of() {
    case "$1" in
        02-aws-http-clients|03-http-client-okhttp|07-fcm-http-v1)
            echo "${PREFIX}01-build-toolchain" ;;
        08-expensive-test-filter)
            echo "${PREFIX}06-db-table-definition" ;;
        *)  echo "$BASE" ;;
    esac
}

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------

git rev-parse --verify --quiet "$BASE" >/dev/null || { echo "no such ref: $BASE" >&2; exit 1; }
git rev-parse --verify --quiet "$SOURCE" >/dev/null || { echo "no such ref: $SOURCE" >&2; exit 1; }

# `git status` rather than `git diff-index`: the latter reports failure when any tracked
# file is unreadable (this repo has a terraform lock file with restrictive permissions),
# which is not the same thing as a dirty tree.
if ! $DRY_RUN && [[ -n "$(git status --porcelain --untracked-files=no 2>/dev/null)" ]]; then
    echo "working tree is dirty; commit or stash first" >&2
    exit 1
fi

START_REF="$(git symbolic-ref --quiet --short HEAD || git rev-parse HEAD)"

# $BASE moves independently of $SOURCE, so the raw $BASE..$SOURCE diff also contains
# reversals of work $BASE gained after the two diverged. Splitting those out would
# silently revert them. Identify them by asking what $SOURCE actually changed relative
# to the merge base: anything in the diff that $SOURCE never touched is $BASE-only and
# belongs to no bucket.
MERGE_BASE="$(git merge-base "$BASE" "$SOURCE")"
TOUCHED_BY_SOURCE="$(git diff --no-renames --name-only "$MERGE_BASE" "$SOURCE" | sort)"
ALL_RAW="$(git diff --no-renames --name-only "$BASE" "$SOURCE" | sort)"

EXCLUDED="$(comm -23 <(echo "$ALL_RAW") <(echo "$TOUCHED_BY_SOURCE"))"
ALL_CHANGED="$(comm -12 <(echo "$ALL_RAW") <(echo "$TOUCHED_BY_SOURCE"))"

if [[ -n "$EXCLUDED" ]]; then
    echo "not carried into any PR — $BASE gained these after $SOURCE diverged:"
    echo "$EXCLUDED" | sed 's/^/  /'
    echo
fi

# Every remaining changed path must land in exactly one bucket. This is the guard that
# makes a path-based split safe: git does the pathspec matching, so the check agrees
# exactly with what the apply step will select.
COVERAGE="$(mktemp)"
trap 'rm -f "$COVERAGE"' EXIT

for ((i = 0; i < N; i++)); do
    # shellcheck disable=SC2086
    git diff --no-renames --name-only "$BASE" "$SOURCE" -- ${PATHS[$i]} \
        | sed "s|\$|	${SLUG[$i]}|" >> "$COVERAGE"
done

MISSING="$(comm -23 <(echo "$ALL_CHANGED" | sort) <(cut -f1 "$COVERAGE" | sort -u) || true)"
DUPLICATED="$(cut -f1 "$COVERAGE" | sort | uniq -d || true)"

# A bucket claiming a $BASE-only file means both sides changed it. Applying the bucket
# would revert $BASE's version, so refuse: $SOURCE needs merging with $BASE first.
CLAIMED_EXCLUDED=""
[[ -n "$EXCLUDED" ]] && CLAIMED_EXCLUDED="$(comm -12 <(echo "$EXCLUDED") <(cut -f1 "$COVERAGE" | sort -u) || true)"
if [[ -n "$CLAIMED_EXCLUDED" ]]; then
    echo "ERROR: these files changed on both $BASE and $SOURCE, and a bucket claims them:" >&2
    echo "$CLAIMED_EXCLUDED" | sed 's/^/  /' >&2
    echo "Merge $BASE into $SOURCE first so the split carries the resolved version." >&2
    exit 1
fi

if [[ -n "$MISSING" ]]; then
    echo "ERROR: changed files not claimed by any bucket:" >&2
    echo "$MISSING" | sed 's/^/  /' >&2
fi
if [[ -n "$DUPLICATED" ]]; then
    echo "ERROR: changed files claimed by more than one bucket:" >&2
    while read -r f; do
        [[ -z "$f" ]] && continue
        echo "  $f -> $(grep -F "$f	" "$COVERAGE" | cut -f2 | tr '\n' ' ')" >&2
    done <<< "$DUPLICATED"
fi
[[ -n "$MISSING" || -n "$DUPLICATED" ]] && exit 1

echo "coverage OK: $(echo "$ALL_CHANGED" | wc -l | tr -d ' ') changed files across $N buckets"
echo

# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------

for ((i = 0; i < N; i++)); do
    # shellcheck disable=SC2086
    stat="$(git diff --no-renames --shortstat "$BASE" "$SOURCE" -- ${PATHS[$i]})"
    printf '%-30s %s\n' "${PREFIX}${SLUG[$i]}" "${TITLE[$i]}"
    printf '%-30s %s  (base: %s)\n' "" "${stat# }" "$(parent_of "${SLUG[$i]}")"
done
echo

$DRY_RUN && exit 0

# ---------------------------------------------------------------------------
# Build the stack
# ---------------------------------------------------------------------------

restore() { git switch --quiet "$START_REF" 2>/dev/null || true; }
trap 'rm -f "$COVERAGE"; restore' EXIT

# Creating the stack and publishing it are separate phases. Re-running with --push or
# --pr against an already-built stack must publish it, not demand that it be rebuilt.
existing=0
for ((i = 0; i < N; i++)); do
    git rev-parse --verify --quiet "${PREFIX}${SLUG[$i]}" >/dev/null && existing=$((existing + 1))
done

if $FORCE; then
    CREATE=true
elif [[ $existing -eq 0 ]]; then
    CREATE=true
elif [[ $existing -eq $N ]]; then
    CREATE=false
    echo "stack already exists; reusing it (pass --force to rebuild from scratch)"
    echo
else
    echo "ERROR: $existing of $N stack branches exist — the stack is half-built." >&2
    echo "Pass --force to rebuild it from scratch." >&2
    exit 1
fi

# A branch checked out in another worktree cannot be deleted. Catch that up front:
# discovering it halfway through leaves a half-rebuilt stack whose lower branches are
# new commits and whose upper branches still descend from the old ones.
if $CREATE; then
    checked_out="$(git worktree list --porcelain | awk '/^branch /{sub("refs/heads/", "", $2); print $2}')"
    blocked=""
    for ((i = 0; i < N; i++)); do
        branch="${PREFIX}${SLUG[$i]}"
        grep -qx "$branch" <<< "$checked_out" && blocked+="  $branch"$'\n'
    done
    if [[ -n "$blocked" ]]; then
        echo "ERROR: these stack branches are checked out in another worktree and cannot be rebuilt:" >&2
        printf '%s' "$blocked" >&2
        echo "Switch those worktrees off the branches (or remove them) and re-run." >&2
        exit 1
    fi
fi

stack=(); stack_parent=(); stack_index=()

# Buckets are listed in topological order, so a parent branch always exists by the time
# its children are built.
for ((i = 0; i < N; i++)); do
    branch="${PREFIX}${SLUG[$i]}"
    parent="$(parent_of "${SLUG[$i]}")"

    if [[ -n "$ONLY" && "${SLUG[$i]}" != *"$ONLY"* ]]; then
        continue
    fi
    stack+=("$branch")
    stack_parent+=("$parent")
    stack_index+=("$i")

    if $CREATE; then
        git rev-parse --verify --quiet "$branch" >/dev/null && git branch --quiet -D "$branch"

        echo "==> $branch (base: $parent)"
        git switch --quiet --create "$branch" "$parent"

        # Buckets are disjoint and all measured from $BASE, so applying bucket i's diff
        # on top of buckets 0..i-1 never conflicts. --index stages adds, deletes and
        # mode changes in one step; --binary keeps any binary content intact.
        # shellcheck disable=SC2086
        if ! git diff --no-renames --binary "$BASE" "$SOURCE" -- ${PATHS[$i]} \
            | git apply --index --binary --whitespace=nowarn; then
            echo "failed to apply bucket ${SLUG[$i]}" >&2
            exit 1
        fi

        printf '%s\n\n%s\n' "${TITLE[$i]}" "${DESC[$i]}" | git commit --quiet --file=-
    else
        # Reusing branches somebody may have amended since. Confirm each still holds
        # exactly its bucket relative to its parent before publishing it.
        # shellcheck disable=SC2086
        want="$(git diff --no-renames --name-only "$BASE" "$SOURCE" -- ${PATHS[$i]} | sort)"
        have="$(git diff --no-renames --name-only "$parent" "$branch" | sort)"
        if [[ "$want" != "$have" ]]; then
            echo "ERROR: $branch no longer matches bucket ${SLUG[$i]} relative to $parent." >&2
            diff <(echo "$want") <(echo "$have") | sed 's/^/  /' >&2
            echo "Rebuild with --force, or fix the branch by hand." >&2
            exit 1
        fi
        echo "==> $branch (base: $parent) — reused, contents verified"
    fi

    if $DO_BUILD; then
        echo "    verifying: ${VERIFY[$i]}"
        git switch --quiet "$branch"
        if ! ${VERIFY[$i]}; then
            echo "VERIFICATION FAILED on $branch" >&2
            echo "Fix it on this branch, then re-run with --only ${SLUG[$i+1]:-} to continue." >&2
            exit 1
        fi
    fi
done

# ---------------------------------------------------------------------------
# Push and open stacked PRs
# ---------------------------------------------------------------------------

restore  # back to the user's branch; push and gh both work fine from here

# Every branch must be on the remote before any PR is opened, since each PR's base is
# the branch below it and GitHub rejects a base it cannot see.
if $DO_PUSH; then
    echo
    for b in "${stack[@]}"; do
        echo "==> pushing $b"
        git push --force-with-lease --set-upstream origin "$b"
    done
fi

if $DO_PR; then
    command -v gh >/dev/null || { echo "gh CLI not found" >&2; exit 1; }
    echo

    stack_list=""
    for ((i = 0; i < N; i++)); do
        stack_list+="$((i + 1)). \`${PREFIX}${SLUG[$i]}\` — ${TITLE[$i]}"$'\n'
    done

    for ((j = 0; j < ${#stack[@]}; j++)); do
        branch="${stack[$j]}"
        parent="${stack_parent[$j]}"
        i="${stack_index[$j]}"

        if gh pr view "$branch" --json number >/dev/null 2>&1; then
            echo "==> PR already open for $branch; skipping"
            continue
        fi

        body="${DESC[$i]}

---

Part $((i + 1)) of $N in a stack splitting \`$SOURCE\`. Review this PR's diff only —
it is against \`$parent\`, so it shows just this change. Merge the stack bottom-up.

$stack_list"

        echo "==> PR: $branch -> $parent"
        gh pr create --base "$parent" --head "$branch" \
            --title "${TITLE[$i]}" --body "$body"
    done
fi

echo
if $CREATE; then
    echo "done. ${#stack[@]} branches created."
else
    echo "done. ${#stack[@]} branches reused."
fi
