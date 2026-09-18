# NotebookLM classifier comparison — first-pass protocol

September 18, 2026, before any NotebookLM classification submission.

This is distinct from compiling a policy from course sources. NotebookLM will extract observable assistance features, and the unchanged Java controller will apply each saved policy. Do not ask NotebookLM to solve the student requests or let its policy knowledge replace the controller.

Use three fresh private notebooks, one per existing suite: 36 original cases, 24 challenge cases, eight bookkeeping cases. Each notebook receives exactly one Markdown source containing the **unlabeled** requests from the saved revised Luna prompt: ID, assignment, prompt, empty history. No course documents, generated policies, other model results, gold labels, or evaluation report are uploaded. This avoids contamination from the policy-generation conversations. Cases are already synthetic development/regression data, not a new held-out test.

Keep the exact common classifier instruction prefix before `REQUESTS JSON` from the revised Luna runs; replace inline requests with a direction to extract every record from the selected uploaded source. Append the same features JSON schema as text. Save the submitted prompt, source, raw response, upload confirmation, notebook title, displayed product/model information, and browser submission/completed-observation times. Backend model, token usage, and cost are unknown unless reliably exposed. Record input/retrieval transport differences from Luna rather than claim byte-identical model inputs.

Run one submission per notebook. Do not keep sampling until the result passes. Only remove an outer Markdown code fence, if present. Preserve missing IDs, duplicate IDs, invalid JSON, and policy/model refusals as failures; no semantic correction or invented features. Stop after the three primary attempts; any later repair or diagnosis must be separately declared.

Validate case coverage and features using the Java evaluation path. Score all valid suites against the same policies used for Luna, Terra, and Jev, and report confusion matrices, unsafe allowances, false denials, excess clarifications, and structural usability. An unusable output has no invented semantic score. Existing Luna/Terra batches and Jev's per-case API interface differ from this browser retrieval workflow; timings and model-quality claims must reflect those differences.

## Execution notes

All three primary browser submissions are now complete. The first two returned all 36 and 24 IDs; the third returned an empty `items` array. No repair was attempted. The [final report](experiments/2026-09-18/final-report.md) preserves that coverage failure.

All submissions reused the same actual 2,857-character prompt (FNV-1a `393796cc`). The initial preparation script serialized equivalent schemas in differing property orders for the later suites; those unused draft strings are preserved as `prepared-prompt-original.txt`. Artifact verification caught that they were not the strings actually submitted. Saved `browser-prompt.txt` files were corrected to the common observed prompt, and the preparation script now reuses one schema serialization. No model input or result was changed, and no extra model call was made. Raw clipboard captures were checked against observed character counts and checksums before scoring; public manifests additionally record SHA-256 hashes of the saved artifacts.
