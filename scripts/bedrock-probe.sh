#!/usr/bin/env bash
#
# Probe whether a specific Amazon Bedrock model actually works for the two things our LlmAccess
# contract tests care about most: plain text generation, and tool use. This is the fast way to
# vet a candidate model id BEFORE adding it to the live test matrix (see
# ai-bedrock/src/jvmTest/.../integration/), so the matrix only ever contains ids we've confirmed
# invoke — never ids that turn the suite red for the wrong reason (retired model, no access,
# needs an inference profile, or a model-side tool-use defect like Amazon Nova's).
#
# Usage:
#   scripts/bedrock-probe.sh <model-id> [region] [profile]      # probe one model (text + tools)
#   scripts/bedrock-probe.sh --list      [region] [profile]     # list invocable ids in the account
#
# Examples:
#   scripts/bedrock-probe.sh us.anthropic.claude-haiku-4-5-20251001-v1:0
#   scripts/bedrock-probe.sh qwen.qwen3-32b-v1:0 us-west-2 lk
#   scripts/bedrock-probe.sh --list us-west-2 lk
#
# Interpreting results:
#   - "TOOL USE: ok" + a toolUse block  -> add to the matrix with tool suites enabled.
#   - "TOOL USE: FAILED" but "TEXT: ok" -> text/streaming only (like Nova); enable text suites,
#                                          leave tool suites off for that model.
#   - both FAILED                       -> not invocable here (access/region/id); don't add it.
set -uo pipefail

REGION_DEFAULT="us-west-2"
PROFILE_DEFAULT="lk"

if [[ "${1:-}" == "--list" ]]; then
  region="${2:-$REGION_DEFAULT}"; profile="${3:-$PROFILE_DEFAULT}"
  echo "# Cross-region inference profiles (invoke these ids directly):"
  aws bedrock list-inference-profiles --region "$region" --profile "$profile" \
    --query "inferenceProfileSummaries[].inferenceProfileId" --output text 2>/dev/null | tr '\t' '\n' | sort
  echo
  echo "# On-demand foundation models (ACTIVE, text output):"
  aws bedrock list-foundation-models --region "$region" --profile "$profile" \
    --query "modelSummaries[?modelLifecycle.status=='ACTIVE' && contains(outputModalities, 'TEXT')].modelId" \
    --output text 2>/dev/null | tr '\t' '\n' | sort
  exit 0
fi

model="${1:?model id required (or --list). See header for usage.}"
region="${2:-$REGION_DEFAULT}"
profile="${3:-$PROFILE_DEFAULT}"

common=(--region "$region" --profile "$profile" --model-id "$model")

echo "== Probing $model  (region=$region profile=$profile) =="

echo "-- TEXT --"
if aws bedrock-runtime converse "${common[@]}" \
    --messages '[{"role":"user","content":[{"text":"Reply with exactly the word: pong"}]}]' \
    --query 'output.message.content[0].text' --output text; then
  echo "TEXT: ok"
else
  echo "TEXT: FAILED"
fi

echo "-- TOOL USE --"
# Mirrors the exact request our tool tests send: system prompt + a zero-parameter tool + a
# question the model should answer by calling it. This is the request that exposes Nova's
# "Model produced invalid sequence as part of ToolUse" defect.
if aws bedrock-runtime converse "${common[@]}" \
    --system '[{"text":"You are a helpful assistant. Use their name, Clarence."}]' \
    --messages '[{"role":"user","content":[{"text":"Hi, what time is it?"}]}]' \
    --tool-config '{"tools":[{"toolSpec":{"name":"get-time","description":"Get the current time","inputSchema":{"json":{"type":"object","properties":{}}}}}],"toolChoice":{"auto":{}}}' \
    --query 'output.message.content' --output json; then
  echo "TOOL USE: ok"
else
  echo "TOOL USE: FAILED"
fi
