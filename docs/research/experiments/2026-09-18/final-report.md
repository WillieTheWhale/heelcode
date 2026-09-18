# HeelCode policy-engine experiment: findings and implementation

September 18, 2026. This report closes the requested prototype-and-comparison experiment, not the broader university research project or production deployment.

## Bottom line

The Java prototype works end to end: it ingests fictional course files, compiles assignment-specific rules, checks student requests against those rules, and can pass approved requests to a real Luna session through persistent stdin/stdout. Live tests demonstrate follow-up context, process-restart recovery, exact retry replay, and separate-session isolation on macOS.

The experiment also found meaningful failures. A policy can contain authentic quotations and still misinterpret the rules. A classifier can pass a small benchmark and then fail on a normal follow-up. A probability-based classifier can block everything prohibited while unnecessarily blocking useful requests. Those are important research questions, not just implementation bugs to hide.

The user's added Jev experiment is complete as a **classifier-only baseline**. No Jev policy-generation call was made. The installed TypeSafe skill guided its decomposition into independent typed judgments; it did not justify assuming those judgments are correct.

## What each component does, in plain language

| Component | Everyday description | What exists now |
|---|---|---|
| Course ingestion | Collect the professor's rulebook and keep a fingerprint of each document | Bounded UTF-8 Markdown/text ingestion, stable hashes, refusal of unsupported inputs and symlinks |
| Policy generator | Translate the rulebook into a checklist the computer can apply | Actual Luna, Terra, and NotebookLM-generated policies with assignment overrides and source quotations |
| Policy validator | Check that the checklist is complete and its quotations really exist | All ten activity categories must be covered once per scope; missing rules, unknown activities, and fabricated quotes fail |
| Workspace activation | Attach one reviewed checklist to one course workspace | Explicit activation, policy/source digests, refusal after source changes until regeneration/reactivation |
| Request classifier | Describe the kind of help being requested | Concept, hint, debugging, test ideas, runnable tests, pseudocode, implementation, full solution, writeup, logistics; plus attempt evidence, ambiguity, explicit deception |
| Decision controller | Apply the professor's permissions consistently | Ordinary Java returns allow, deny, or clarify, with feedback and evidence; the classifier does not invent permissions |
| Session store | Remember which conversation and course a request belongs to | Durable policy sessions, bounded recent history, assignment/workspace pinning, request IDs, exact retry replay |
| Gated inference bridge | Open the gate to the answering model only after admission | Explicit JSONL `policy chat` path; real Luna answers for allowed requests; no answer call for denied/unclear requests |
| Evaluation | Keep an answer key separate and measure disagreements | Frozen synthetic cases, raw model evidence, policy oracle, confusion matrices, timing, and preserved failed runs |

This is **not a detector of whether a student understands material**. It describes observable requests and evidence, not a student's mental state. A unit-test request may be permitted in one assignment and prohibited in another where writing tests is the assessed skill. A vague request should generally receive a question, not an accusation.

The distinction between generator and classifier is essential. The generator reads course documents occasionally; the classifier reads each request. Jev's typed probabilities fit the latter role. Its independent Noul questions can mark multiple activities, while code owns the policy decision. [TypeSafe Noul documentation](https://docs.typesafe.ai/primitives/noul), [HTTP API](https://docs.typesafe.ai/api), [guardrails design example](https://docs.typesafe.ai/cookbooks/llm_guardrails).

## Course and experimental design

The fictional SYS301 course has four assignment scopes plus a course baseline. Mini-shell implementation must remain student-authored; an initial permission to generate shell tests is revoked by a later amendment. Allocator-test construction is assessed, so runnable tests are prohibited. Scheduler implementation and tests are permitted, but the final analysis remains student-authored. Ungraded practice allows worked solutions and debugging without a prior attempt. Explicit concealment and misrepresentation remain prohibited.

There are 50 independently specified rule settings: five scopes times ten activities, each checking the effect and attempt requirement. Request tests contain 36 initial cases, 24 challenge cases, and eight bookkeeping regressions: 30 expected allows, 28 denies, and 10 clarifications.

The request models never receive gold decisions or case-category labels. Classifier features are reused across generated policies so we can distinguish a wrong rule from a wrong interpretation of a request. Reusing features across policies does **not** create additional independent model trials.

The benchmark is not fully held out: the development agent authored the fixtures/labels, the rule oracle followed inspection of an early policy, and bookkeeping regressions followed a real failure. These results measure this constructed regression task. There are no real students, grades, independent annotators, learning outcomes, or statistical claims of a general winner.

## Policy generation results

| Generator/workflow | Planned drafts | Usable policies | Correct rule settings per draft |
|---|---:|---:|---|
| Luna through Codex, low effort | 2 | 2 | 50/50; 50/50 |
| Terra through Codex, low effort | 2 | 2 after one local validation recovery | 50/50; 50/50 |
| NotebookLM browser product | 2 | 2 | 49/50; 49/50 |
| Jev | Not tested for this role | Not applicable | Not applicable |

Terra's first response was saved, but a simultaneous JAR rebuild broke local validation. The unchanged saved response was recovered and validated; missing timing was not invented. This was an implementation failure, not discarded model evidence. [Original comparison and recovery record](comparison-report.md).

NotebookLM draft 1 incorrectly required an attempt for debugging in ungraded practice. Draft 2 missed the September 10 amendment and permitted executable shell tests. Both passed structural validation and exact-quote checks. The second draft's old permission was genuinely quoted from a source, but no longer valid. This directly demonstrates the difference between **a real citation** and **a correct interpretation of the current policy**.

All six source files were uploaded and selected in each fresh policy notebook. The interface displayed “Gemini Notebook”; its backend model and account tier were not established. An 8,299-character inline-source prompt could not be submitted. Before generation, both runs used the same 2,576-character instruction/schema prompt with the course provided through uploads. This is a browser-product/retrieval comparison, not a controlled base-model comparison. No API access, cloud project, paid upgrade, or subscription entitlement was assumed. [Browser protocol](../../notebooklm-comparison-protocol.md), [computed policy comparison](notebook-policy-comparison/comparison.json).

## Classifier results, holding the policy correct

These use the unchanged Java controller and any of the four correct Luna/Terra-generated policies.

| Classifier workflow | Original 36 | Challenge 24 | Bookkeeping 8 | Overall outcome |
|---|---:|---:|---:|---|
| Luna, revised prompt | 36 correct | 24 correct | 8 correct | 68/68 matched |
| Terra, revised primary run | 33 correct | 24 correct | 8 correct | 65/68 matched |
| Jev 1.13.0, first-pass questions | 29 correct | 19 correct | 6 correct | 54/68 matched |
| NotebookLM, three fresh notebooks | 36 correct | 24 correct | **No items returned** | 60 correct; 8 unavailable due to failed batch |

NotebookLM's returned 60 decisions matched the labels, but its overall workflow did **not** complete all 68. The eight-case response was literally `{"items": []}`. Java rejected it as missing evaluation IDs. No semantic score was assigned to the missing cases, and no repair or replacement run was used. Only two of three planned classifier batches were usable. Calling this “100% classifier accuracy” without the coverage failure would be misleading. [Classifier protocol](../../notebooklm-classifier-protocol.md), [complete classifier comparison](notebook-classifier-comparison/comparison.json).

Terra's three primary errors included an unsafe permission for a submission-ready fairness paragraph, plus two false denials. Their rationales resembled neighboring batch cases; attribution interference is a plausible explanation, not a proven cause. A declared repeat batch and three isolated checks were correct. Those diagnostics did not replace the primary failure. [Revised Luna/Terra evidence](revised-comparison-report.md).

Jev returned all 68 calls successfully, with complete typed responses. It made no prohibited-to-allowed error in this sample, but unnecessarily clarified 12/30 allowed requests. It also clarified one expected denial and denied one expected clarification. For example, it recognized “Debug my practice code” as debugging while marking the request unclear, despite the practice policy not requiring an attempt. Missing information for answering is not necessarily missing information for classifying the assistance.

Jev's >=0.5 thresholds were frozen before the run, not calibrated on independent course data. An empty activity set becomes unclear rather than receiving permission. Raw probabilities and all errors remain saved. Jev-generated explanations were not fabricated: its feature rationale explicitly describes the code conversion. [Detailed Jev report](jev-report.md).

## Why separating the two stages matters

| Generated policy | Luna classifier | Terra classifier | Jev classifier | NotebookLM classifier |
|---|---:|---:|---:|---:|
| Any correct Luna/Terra policy | 68/68 | 65/68 | 54/68 | 60/60 available; 8 missing |
| NotebookLM policy draft 1 | 67/68 | 64/68 | 54/68 | 59/60 available; 8 missing |
| NotebookLM policy draft 2 | 64/68 | 60/68 | 53/68 | 57/60 available; 8 missing |

With NotebookLM policy draft 2, Luna's otherwise-correct features led to **four prohibited requests being allowed**. Terra produced six unsafe allowances, and NotebookLM's available features produced three. Jev produced no unsafe allowance in that cross-product, but its clarification behavior and other detected categories masked some policy errors. That does not make the underlying generated policy correct.

This is the main architectural result: improving the classifier cannot compensate reliably for a wrong rulebook. The professor needs a readable policy review/approval step, and evaluation must test both stages separately.

## Timing and resources

| Workload | Observed measurement | Interpretation |
|---|---|---|
| Luna policy drafts | 31.110 s; 24.475 s | Whole Codex adapter calls |
| Terra policy draft 2 | 24.109 s | Draft 1 timing unavailable after local failure |
| NotebookLM policy drafts | Completion observed by 130.882 s; 153.525 s | Upper bounds including observation delay; not service latency |
| Luna classifier batches, 36/24/8 cases | 35.474 s / 32.811 s / 13.117 s | Batch jobs, not individual-request latency |
| Terra classifier batches, 36/24/8 cases | 36.708 s / 25.389 s / 16.135 s | Same batch qualification |
| Jev classifier, 68 individual calls | Median 404 ms; mean 508 ms; p95 1,193 ms | Client-observed HTTP round trips; 13 questions per request |
| NotebookLM classifier batches | Completion observed by 84.703 s / 112.282 s / 100.720 s | Third batch unusable; browser upper bounds only |
| Real guarded Luna continuation | About 12.8–14.9 s in the continuity test | Classification plus answer generation and local work |
| Exact saved-answer replay | 0.003 s in that test | No new classifier or answer call |

Jev used 142,080 input tokens and 15,504 output tokens. At the documented September 18 rate of $0.042 per million input tokens, with output tokens free, this is an estimated **$0.00597** in token charges—not a verified invoice. [TypeSafe model/pricing page](https://docs.typesafe.ai/models). Luna/Terra metadata preserves usage, but subscription usage is not converted into an invented per-call dollar cost. NotebookLM does not expose reliable token/cost measurements here.

These transports, batching strategies, model contexts, and latency measurements differ. In particular, dividing a batch duration by its case count is not the response time a student would experience. A fair latency study needs one-request-at-a-time measurements under comparable conditions.

## Piping, persistence, and possible frontends

The reauthenticated ChatGPT subscription successfully powered real Luna answers through HeelCode. A future Electron or Java frontend can launch the guarded process:

```sh
heelcode policy chat /absolute/path/to/activated-workspace gpt-5.6-luna openai/gpt-5.6-luna
```

It sends one JSON object per line and reads one response per line. The first request uses an empty session ID. Later requests send the returned `hps_...` ID; the bridge tracks the associated inference `ses_...` ID. New questions need new request IDs; identical retries replay saved results. Conflicting ID reuse, assignment switches, and foreign-workspace sessions fail closed.

Actual tests verified two answers on one open pipe, recall after replacing the process, exact replay without new model runs, a denied request without inference, and independent-session inability to recall another conversation's random label. Raw `heelcode run --session ses_... --format json` also preserved context across invocations, but raw `run` is one EOF-delimited request per process. The persistent JSONL server is the explicit `policy chat` path. [Session report and transcripts](session-verification.md).

Responses are buffered, not token-streamed. The session history supplied to extraction is bounded. A durable pending marker avoids blindly replaying inference after an uncertain crash; it does not guarantee exactly-once remote execution. Windows has not been smoke-tested, although the Java protocol is platform-independent. No frontend was built.

## Verification and limitations

The package has 25 passing offline Java tests: 12 engine, seven bridge, six Jev adapter tests. Research scripts pass package-level `bun typecheck`; pushes also run the repository's typecheck hook. Evidence scripts reject overwriting existing output directories and verify captured data/input hashes. The API key was used in process memory, never committed or saved in experiment artifacts. Browser account identifiers and private notebook URLs are excluded from public evidence.

Recorded operational problems include earlier provider authentication/funding failures, the local JAR-rebuild failure, a test-script assertion error, Luna's live empty-category reply, Terra's batch errors, NotebookLM's policy interpretation errors, and NotebookLM's empty classifier batch. Successful later runs do not erase them.

The prototype is not a secure enforcement boundary for a student who controls local files. Bare TUI/raw-run use remains unguarded. There is no output-content classifier, tool-action policy guard, signed instructor policy, production access-control system, PDF/DOCX ingestion, upload portal, or fine-tuned encoder. Policies are generated on explicit ingestion and become stale after source changes; there is no automatic watcher. Exact quotations and hashes are integrity checks, not authority or semantic correctness proofs.

## What to take to the professor

The implementation and comparisons support a narrower, defensible research direction: **source-grounded policy compilation plus observable request admission**, evaluated separately for correctness, useful assistance, uncertainty, and failure recovery. They do not yet establish that the system detects mindful learning or improves learning outcomes.

Keep Luna as the working baseline for the next prototype iteration. Jev is worth further study because its typed interface, observed latency, and token estimate suit an inexpensive admission stage, but the current question design creates too much unnecessary friction. NotebookLM's browser workflow successfully handled many cases, yet missed critical policy details and returned an empty batch; do not activate its output without review. None of these observations establishes a universal model ranking.

A next study should use independently labeled instructor examples; distinguish missing answer material from an unknown request category; calibrate thresholds on a separate validation set; evaluate adversarial paraphrases, context-dependent follow-ups, and multilingual requests; and measure both inappropriate assistance and useful assistance withheld. A later classroom study would additionally need consent, data governance, instructor-approved policy handling, and appropriate IRB review. Those are future research/deployment decisions, not unperformed tasks hidden inside this completed prototype experiment.
