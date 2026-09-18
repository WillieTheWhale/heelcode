# NotebookLM comparison protocol

Prepared September 18, 2026; browser procedure amended before generation. The signed-in browser is usable, and the user's original authorization expressly permits browser testing. The earlier assumption that student verification blocked all access was unnecessary. Six fictional source files are now uploaded. No consumer API access or paid upgrade is assumed.

## Question and comparison boundary

Can the notebook product turn the same fictional course sources into a complete, source-grounded policy that the same Java validator and decision controller can use?

This is a **product/workflow comparison**, not a controlled comparison of underlying base models. Luna/Terra use a tool-free Codex adapter with a structured-output schema; the notebook browser product may use retrieval, hidden prompts, an undisclosed model, and only a schema supplied in text. Record these differences. Do not guess a model/version or assume that the product is outdated.

Google's current documentation describes notebook-management APIs for **Gemini Notebook Enterprise** and lists Enterprise setup and licensing as prerequisites. It does not establish that a consumer student subscription provides the desired policy-generation API. Use the user-approved browser workflow if programmatic access is not independently verified; do not create a cloud project, buy licenses, or change billing to run this comparison. [Google's notebook API documentation](https://docs.cloud.google.com/gemini/enterprise/notebooklm-enterprise/docs/api-notebooks)

## Frozen input and output contract

Use only the six files in `packages/policy-engine/fixtures/systems/course`, verified against the [saved course snapshot](experiments/2026-09-18/luna-policy-01/bundle.json). Do not upload student data, the repository, research results, model outputs, gold cases, the rule oracle, or these evaluation instructions as notebook sources.

| Source filename | SHA-256 of UTF-8 source text |
|---|---|
| `00-syllabus.md` | `4ded3321d1d931cde7e6630406db3658cbee41fb9a1853b0ff10ae9d2c5c5200` |
| `10-a1-shell.md` | `7947b68ba95510c13dbc49a7d967bd9b7e2d07ad1be231f6d9c32dfe21d0fb65` |
| `20-a2-allocator.md` | `a7671eaa5f64c57d37cc2cbe685307e9a632d9478c94e0d2031c587262a1756c` |
| `30-a3-scheduler.md` | `72c42031ae1e0add8de3e884098bcabc06e3d3dfd16531b70e9c2b82275bdaea` |
| `40-amendment.md` | `a294f8df10dc4dd6a9efd2375a4cd7de69c930fbaa115916db1dcfceb08deea7` |
| `50-practice.md` | `7598d27b1e0a2a935fceff44af873d402cf00160e263410d813b562c96c0335a` |

The course ID is `sys301`; the Java course digest is `4eca5b9ad4c1018853e543f0c49b0bc09d894e0ae387f74fd14ffba746015f46`. That digest is produced by the Java course serialization, not by concatenating the file hashes.

The common generation instructions and complete source JSON are preserved in [the original prompt](experiments/2026-09-18/luna-policy-01/prompt.txt), SHA-256 `b7a2e0c0fab79b68b96172ceb2812c452bffd9eb65af86a8dcf9b734e4ce405d`. The output schema is [schema.json](experiments/2026-09-18/luna-policy-01/schema.json), file SHA-256 `412390735f560fb89aebe41b37737d6002327e7266850ea5e86a4de46215e761`.

The initially prepared 8,299-character prompt (full course JSON plus schema) left the browser Submit button disabled, including after filling the input again. No generation was submitted with it. Before the first generation, adapt both planned runs to the 2,576-character prompt: retain the common instruction prefix before `COURSE JSON`, then specify `Course ID: sys301. Use the six selected uploaded sources; source IDs are their exact filenames.`, followed by `OUTPUT JSON SCHEMA:` and the compact schema. This enables Submit. The exact maximum input length was not investigated or claimed.

Preserve the complete actual submitted text as `browser-prompt.txt`. Keep the unmodified original prompt separately as `prompt.txt` for the existing source-validation command. Do not describe those files as byte-identical. The schema-as-text and source retrieval from uploads rather than inline course JSON are declared interface differences from the Codex runs.

## Live procedure once access is ready

1. Inspect the signed-in product and its actual capabilities. Record date/time, displayed product name, any displayed model/settings, account tier if visible, and the notebook identifier privately. Do not expose account email or credentials in evidence. If no model is displayed, record `unknown`.
2. Create a private research notebook containing only the six fictional source files. Do not share it. Confirm every source is imported and ready. If Markdown upload is unsupported, use the product's supported text-source input while retaining the exact text and filename as its title; record this transport difference.
3. Submit the prepared browser prompt once. Record time from submission to visibly completed response separately from source-upload/indexing time. Preserve raw response text and an appropriate screenshot. Do not use outside web sources or unrelated notebooks. Record any automatic retrieval behavior that cannot be disabled.
4. Repeat in a fresh notebook for a second generation sample, with the same sources and prompt. Two attempts are the planned current-policy comparison; do not keep sampling until a passing policy appears.
5. Preserve failures, timeouts, truncation, and invalid JSON. A raw JSON answer or an answer with one outer Markdown code fence may be parsed without semantic edits. Removing the outer fence is a formatting normalization and must be recorded. Do not rewrite rules, fix quotes, or silently strip inline citations to make validation pass.
6. If the first response is unusable, it counts as a failed first-pass output. An optional single format-only repair request may be reported separately, with its exact prompt and raw response. Never replace the first attempt with the repaired one or mix assisted and unassisted success rates.

## Validation and scoring

For each run, save the original common prompt as `prompt.txt` and the mechanically extracted JSON as `response.json` in a new run directory. Keep `browser-prompt.txt`, raw output, screenshots, and interface metadata alongside them. Then use the existing Java validator from `packages/policy-engine`:

```sh
./run validate sys301 fixtures/systems/course /absolute/path/to/new-notebook-run
```

This verifies the current sources against the common prompt and validates structure, activity coverage, and exact citation presence. It does not prove that the browser received the prompt; that provenance comes from the saved browser interaction evidence. It does not prove semantic policy correctness.

For a valid policy, run `score-policy` using the existing 50-setting oracle. Then run `evaluate` separately for the 36 original cases, 24 challenge cases, and eight bookkeeping regressions, first using the saved revised Luna features and then the saved revised Terra features. Do not send these labels, oracle, or model features to NotebookLM. This keeps the **policy-generator** comparison separate from **request-extractor** quality. Also display the earlier Terra batch-extraction errors rather than attributing them to NotebookLM's generated policy.

For an invalid policy, report structural usability failure; do not invent missing rules or silently assign 0/50 semantic accuracy to an unparsed output. If manual semantic review is later added, label it as a separate measure with the reviewer and rubric recorded.

Record first-pass valid output rate, correct rule settings, false permission grants, false restrictions, request decisions under each saved extractor, citation validity, and observed browser latency. Token counts and dollar cost are `unavailable` unless the product exposes reliable measurements. Do not convert a subscription price into per-request inference cost.

## Interpretation and stopping rule

The common corpus is fictional and small; the labels were written by the development agent, and the rule oracle was authored after inspecting an early model output. Results measure this constructed task, not students' understanding or classroom learning. UI/retrieval differences and unknown backend model identity limit causal comparisons.

After the two planned generations and scores, consolidate the NotebookLM results with the existing Luna/Terra and session reports. If access or export is unavailable, preserve the specific blocker and ask the user for direction; do not substitute generic Gemini output or describe an unrun comparison as complete.
