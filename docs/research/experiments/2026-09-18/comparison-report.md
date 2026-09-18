# HeelCode policy engine: Luna/Terra and guarded-session checkpoint

September 18, 2026. **Interim report: the Java prototype and comparative policy tests work; the overall goal is not complete.** NotebookLM and successful end-to-end answer-session tests remain pending.

## Main finding

Luna and Terra both produced the expected rule settings on this small fictional course, in two policy-generation runs each. Both also produced features that led to all 60 expected request decisions under every generated policy. This experiment therefore **does not establish a quality winner**. It demonstrates that the architecture can represent assignment exceptions, later amendments, and cautious handling of conflicting sources. It does not demonstrate general classroom accuracy, detect cheating reliably, or measure student understanding.

The latest implementation also owns the boundary between a policy decision and HeelCode inference. Real Luna classifications of denied and unclear requests never launched the answer model. That blocked path passed a persistent-pipe and process-restart test. Successful allowed-answer inference is not yet verified because the available HeelCode providers failed; those failures are preserved, not counted as passes.

## Components, in everyday language

| Component | What it does | Important limit |
|---|---|---|
| Course snapshot | Reads the instructor's text files and records exactly what they said. | Markdown/text only; PDF, Word, and LMS ingestion are not implemented. |
| Policy generator | Turns those files into a rulebook for each assignment, with quoted supporting passages. | A real quote can still be misinterpreted. Instructor review is necessary. |
| Policy validator | Checks completeness, non-overlapping categories, and whether citations actually exist. | It checks structure and source grounding, not the instructor's intent. |
| Request extractor | Identifies what the student is asking for: an explanation, debugging, tests, code, a whole solution, etc. | It observes the request; it cannot know whether the student understands the material. |
| Decision controller | Applies the relevant rules and returns allow, deny, or clarify. | Its result depends on both correct extraction and a correct policy. |
| Durable admission session | Remembers earlier requests and decisions using a stable `hps_...` ID. | Only the last six admission turns enter extraction context; full local history is capped at 200 turns. |
| Gated-chat bridge | Sends allowed requests to HeelCode and maps policy IDs to engine `ses_...` IDs. | Only this explicit path is guarded. Raw `heelcode run` and the TUI remain unguarded. |
| Experiment runner | Saves inputs, outputs, timing, and comparisons against an explicit expected result. | These expected results are synthetic developer annotations, not independent research ground truth. |

The core is Java targeting Java 17, built/tested on macOS with OpenJDK 25. Bun scripts run experiments and collect evidence. The same Java code/JAR and JSON protocol can be used on Windows, but Windows execution and packaging are **not tested**. The current convenience launcher is POSIX.

No fine-tuned encoder is implemented or evaluated. Luna/Terra extraction is a baseline that could later be compared with a trained encoder using independently labelled examples; 60 synthetic cases are not an adequate basis for claiming such a model generalizes.

## What was compared

1. Six fictional SYS 301 documents supply a course baseline and four explicit assignment/practice scopes. Ten activity categories produce 50 rule settings. A setting includes the action and whether a concrete student attempt is required.
2. Each generator received the same course snapshot, instruction template, and policy schema. There were two current-policy generations with `gpt-5.6-luna` and two with `gpt-5.6-terra`, all at low reasoning effort. The four generator prompt files are byte-identical (SHA-256 `b7a2e0c0fab79b68b96172ceb2812c452bffd9eb65af86a8dcf9b734e4ce405d`).
3. Each classifier extracted features once for the 36-case initial suite and once for the 24-case challenge suite. Expected labels were excluded from extraction prompts. Instruction templates and semantic input objects match across models; JSON object-key order differs, so the classifier prompt files are not byte-identical. The Codex wrapper can also contribute model-specific context overhead.
4. The same frozen Java controller scored each of four generated policies against each classifier's saved features. This separates policy generation from request interpretation and avoids paying for identical extraction repeatedly.
5. The rule oracle was authored **after inspecting the first Luna policy**. The challenge suite was authored after the initial scores. Neither is independent or preregistered. No failed cases were removed, but this workflow is susceptible to benchmark-design bias.

Real calls used the authenticated local Codex CLI adapter, with an isolated directory, read-only sandbox and tools disabled. No Sol/Astra model was used in the product loop. The provider's documentation identifies Luna and Terra and their availability, but does not validate this experiment's results. [OpenAI model documentation](https://learn.chatgpt.com/docs/models)

## Policy and request scores

| Generator | Generation samples | Correct rule settings in each sample | Unexpected extra scopes |
|---|---:|---:|---:|
| Luna | 2 | 50/50; 50/50 | 0; 0 |
| Terra | 2 | 50/50; 50/50 | 0; 0 |
| NotebookLM | 0 | Not measured | Not measured |

| Policy generator | Request extractor | Correct decisions, generation 1 | Correct decisions, generation 2 | Forbidden requests allowed, each sample | Permitted requests denied, each sample |
|---|---|---:|---:|---:|---:|
| Luna | Luna | 60/60 | 60/60 | 0/24 | 0/27 |
| Luna | Terra | 60/60 | 60/60 | 0/24 | 0/27 |
| Terra | Luna | 60/60 | 60/60 | 0/24 | 0/27 |
| Terra | Terra | 60/60 | 60/60 | 0/24 | 0/27 |

Each combination's confusion matrix is identical:

| Expected / observed | Allow | Deny | Clarify |
|---|---:|---:|---:|
| Allow | 27 | 0 | 0 |
| Deny | 0 | 24 | 0 |
| Clarify | 0 | 0 | 9 |

These are **60 unique synthetic requests reused across eight combinations, not 480 independent examples or trials**. There is one extraction sample per model/suite. Agreement on final decisions also does not prove every extracted feature or rationale is correct; features were not independently annotated and scored. The benchmark has reached a ceiling, so it cannot support a Luna-versus-Terra quality ranking.

Machine-readable results: [comparison summary](comparisons.json). Each policy directory contains `rule-evaluation.json` and the four extractor/suite evaluation files with individual outcomes.

## Dynamic documents and source conflicts

The source amendment experiment already showed a meaningful policy change. The original shell handout allowed generated executable tests. With the later instructor amendment included, that activity became denied. Removing the amendment and running a real generation call restored permission. The controller does not hardcode that assignment's answer. [Mutation evidence](luna-before-amendment-01/dynamic-summary.json)

A new live Luna experiment added two equally dated allocator notices: one explicitly allows generated executable tests, while the other explicitly prohibits them. The generator produced `clarify`, citing both notices. A real subsequent classifier call identified a request for executable tests, and the controller returned `policy_uncertain`, `forward:false`, and a request for instructor clarification. It did not use filename ordering to manufacture precedence. This is one successful constructed conflict, not a general conflict-resolution guarantee. [Conflict evidence](luna-conflict-01/conflict-summary.json)

## Piping, session IDs, and enforcement

| Path | Actual behavior | Verification status |
|---|---|---|
| `heelcode policy check` | One JSON request at EOF; durable policy session. | Unit and live gate tests passed. |
| `heelcode policy stdio` | Multiple JSON requests on one open pipe; one response per line; `hps_...` continuation and restart. | Live Luna follow-up/context, denial, and exact replay passed. |
| `heelcode policy chat` blocked path | Gate decides first; deny/clarify never invokes answer inference. | Live Luna test passed: three requests, same ID, restart replay, no inference run directories. |
| `heelcode policy chat` allowed path | Maps a policy session to a HeelCode engine session and returns answer text. | Implemented and tested with controlled test solvers; **not yet passed with real answer inference**. |
| `heelcode run --format json --session ses_...` | One EOF-delimited request per process; same engine ID can be reused. | Real memory/restart/isolation test **not passed**; provider failures interrupted it. |

Bridge responses are buffered until the model turn ends. This is framed request/response piping, not incremental token streaming. A future Electron or Java frontend can use the protocol, but should not treat it as production-ready yet. [Protocol and runnable examples](../../../../packages/policy-engine/README.md)

The bridge persists a pending marker before inference. If a crash leaves the outcome unknown, it refuses to automatically repeat the remote call. Definitive responses are saved so an exact retry can return the original response without a second call. This is conservative retry handling, **not a claim of exactly-once remote execution**. If the first request used an empty session ID and its response was lost, the caller does not yet have the ID needed to deduplicate it.

The bridge also checks course/policy freshness after inference and withholds output if sources changed. Text-only answer calls deny tool use. There is no semantic output filter; including the policy in the model prompt does not prove the answer complies. A local user can edit local policy files, so hashes are drift detection, not an authorization boundary.

Evidence: [live admission transcript](policy-sessions-01/transcript.json), [blocked bridge transcript](bridge-blocked-01/transcript.json), [blocked bridge assertions](bridge-blocked-01/summary.json).

## Failures and missing evidence

**Terra generation 1 had a local validation failure after model output was saved.** A simultaneous rebuild replaced the JAR being used by the process, producing a Jackson `NoClassDefFoundError`. The original response was preserved. After rebuilding, the recovery command verified that the saved prompt exactly matched the current source-derived prompt and validated the unchanged response. Its rule scores are usable, but end-to-end timing is missing; it is not presented as a clean successful invocation. A second Terra generation ran cleanly. Do not rebuild the JAR during live runs. [Recovery record](terra-policy-01/recovery.md)

**OpenAI answer inference failed.** The saved run returned an expired-token response. A separate ordinary-launch diagnostic returned a socket-connection error. Those symptoms do not establish the root cause of the user's provider setup, and a future expiry in local credential metadata would not prove the remote token is accepted. The working Codex classifier sign-in is separate evidence; it does not prove HeelCode's provider sign-in works. [Sanitized failed run](heelcode-sessions-01/first.stdout.jsonl)

**OpenCode Zen Luna also failed**, reporting insufficient account funds. No funds were added and no billing settings changed. This is an availability failure, not a session-continuity pass. [Sanitized failed run](heelcode-sessions-zen-01/first.stdout.jsonl)

**NotebookLM has not been tested.** It remains deferred while Google student verification completes. Consumer/student access must not be assumed to include the Enterprise notebook/source API. Browser-based comparison remains an option, and generic Gemini output will not be mislabeled as NotebookLM output. [Google Enterprise notebook API documentation](https://docs.cloud.google.com/gemini/enterprise/notebooklm-enterprise/docs/api-notebooks)

## Measured time and usage

| Call | Wall time | Input tokens | Output tokens |
|---|---:|---:|---:|
| Luna policy 1 | 31.110 s | 10,180 | 1,262 |
| Luna policy 2 | 24.475 s | 10,181 | 1,174 |
| Terra policy 1, recovered | Unavailable | 11,323 | 1,136 |
| Terra policy 2 | 24.109 s | 11,321 | 1,134 |
| Luna initial 36-request extraction | 37.025 s | 9,783 | 1,838 |
| Terra initial 36-request extraction | 34.952 s | 10,957 | 1,701 |
| Luna 24-request challenge extraction | 35.823 s | 9,536 | 1,462 |
| Terra 24-request challenge extraction | 25.450 s | 10,677 | 1,222 |
| Luna pre-amendment policy | 28.716 s | 10,058 | 1,273 |
| Luna conflicting-notices policy | 25.717 s | 10,348 | 1,242 |

These are observed whole-call times, not throughput guarantees or a statistically supported speed ranking. Batch times are not live per-request latency. The first live admission-pipe trial's three individual calls took 4.205–4.721 seconds, with roughly 8,900 input tokens each; the Codex adapter contributes substantial overhead. Reasoning-token counts are preserved separately in metadata and are not added again here. No dollar costs are inferred from subscription use. Terra's recovered call's usage comes from its saved terminal usage event, not invented metadata.

## Verification and reproducibility

- **18 Java tests passed:** [12 engine tests](unit-tests-current.txt), [6 bridge tests](bridge-blocked-01/unit-tests-current.txt). Earlier test snapshots remain as historical evidence. Bridge mapping/failure tests using a controlled solver are explicitly not live provider tests.
- `bun typecheck` passed in `packages/policy-engine` for the research scripts.
- `script/compare.ts` regenerates the comparisons from the saved policies/features without model calls.
- `script/source-conflict.ts` creates the conflicting documents and runs real Luna generation plus classification. It requires a new output directory and consumes model allowance.
- All generator/classifier directories preserve source-derived prompts, schemas, raw responses, event logs, and usage/timing where available. Expected case labels are stored separately in the fixture directory.
- Evidence is fictional course/student data. Published provider errors have authentication/cookie headers redacted. Raw bridge provider logs remain private in ignored experiment workspaces.

Build and run tests from `packages/policy-engine`, not the repository root. Reproduction scripts and the JSONL contract are documented in the package README. Never overwrite prior evidence or rebuild while a live model run is in progress.

## What remains before completion

1. Restore a working Luna answer-provider connection and pass the real engine memory/restart/isolation test, then the allowed-answer bridge test. Do not substitute controlled solvers for this evidence.
2. Run the NotebookLM comparison when access is ready, keeping the same source snapshot and disclosing interface/schema differences.
3. Strengthen evaluation with independently reviewed policies and labels, realistic multi-turn behavior, and harder source conflicts. The current ceiling does not justify deploying an automatic academic-integrity decision system.
4. Consolidate the final report only after the requested comparisons and end-to-end session checks are complete. This report is a transparent milestone, not that final sign-off.

For now, Luna remains the sensible first implementation target because it meets this limited test and was the user's requested priority. The data do not show that Terra is necessary or that Luna is universally sufficient. A future encoder comparison should be framed as a separate, properly labelled evaluation, not as a conclusion already established here.
