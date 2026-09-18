# Jev classifier experiment — frozen first-pass protocol

September 18, 2026; written before the first TypeSafe API call.

The user added TypeSafe Jev to the **classifier** comparison. It is not a policy generator. Continue Luna and NotebookLM policy-generation comparisons, retain completed Terra evidence, and additionally test NotebookLM as a classifier in a separate source-isolated notebook. A NotebookLM policy-generation score is not a classifier score.

## Design

Use the existing 36 original, 24 challenge, and eight bookkeeping cases unchanged. Send only case ID, assignment, prompt, and empty history: never gold decisions or case-category labels. No student data. The same saved policies and Java decision controller map extracted features to allow, deny, or clarify.

Pin the HTTP model to `jev-1.13.0`. Each case is one API call containing 13 independent Noul questions: ten activity flags plus concrete attempt, unclear assistance, and explicit dishonesty. Multiple activities may coexist. This follows the [TypeSafe skill](https://github.com/typesafe-ai/skills/blob/main/skills/typesafe-ai/SKILL.md), [Noul guidance](https://docs.typesafe.ai/primitives/noul), [HTTP contract](https://docs.typesafe.ai/api), and [guardrails cookbook](https://docs.typesafe.ai/cookbooks/llm_guardrails). The installed skill is local to Codex, not copied into this repository.

Primary conversion: probability >= 0.5 means true. If no activity passes and dishonesty is false, mark unclear rather than admit an empty request. These are declared starting rules, **not validated confidence thresholds**. Save every probability so later calibration or abstention studies can reuse observations, but do not tune thresholds on these results and call them held-out performance. Rationales are fixed code-generated provenance, never invented Jev explanations.

One pass through each suite, sequential calls, no automatic retries or fallback. Stop on HTTP, parsing, or response-validation failure; preserve completed case evidence and the failure. A resumed/repaired run must be separately identified. The primary suite has 68 calls, each with 13 questions; no policy-generation call is made. Any subsequent diagnostic must remain separate from the primary results.

## Evidence and comparison limits

Save request JSON without Authorization headers, raw response, returned model, usage, HTTP status, elapsed wall time, request hash, and derived features. Keep the API key in the process environment only. The adapter uses the fixed HTTPS TypeSafe endpoint with redirects disabled. Do not record the key or account identifiers in source control.

Report all 68 decisions, confusion matrix, false allowances, false denials, clarification rates, failures, and per-call latency. Use actual usage; any dollar estimate must be labeled as an estimate at the documented token rate, not an account charge. [Model/version/pricing documentation](https://docs.typesafe.ai/models).

Luna/Terra's existing offline extraction used a batch of cases with generated JSON; Jev uses one state per case with parallel binary judgments. Inputs and decision policy are comparable, but prompts, interfaces, batching, and rationale capability differ. Do not compare batch latency with Jev's per-request latency or claim an underlying-model causal ranking. NotebookLM's product/retrieval layer and unknown backend require the same caution.

These synthetic, developer-labeled cases are already used for development. The bookkeeping cases were added after a live failure, and the oracle was authored after inspecting an early policy. They are a regression benchmark, not a clean independent test or a measurement of student understanding. Do not infer probability calibration from decision accuracy alone.
