# Local validation recovery

The first Terra generation returned a complete JSON response and a `turn.completed` usage event. While that model call was running, the developer rebuilt the same shaded JAR used by the waiting Java process. On return, Java failed to load `com/fasterxml/jackson/core/json/JsonReadContext`, before it could save normal metadata and validate the policy. The parent process exited 1. This was a local packaging/workflow failure, not a reported model or provider failure.

The exact saved `response.json` was subsequently validated with `heelcode policy validate sys301 SOURCE_DIR RUN_DIR`. That command first verifies that the current source snapshot produces the identical saved prompt. Validation succeeded without editing the response or making another model call; `bundle.json` and `policy.json` were then written.

Token usage is available from `events.jsonl`. Wall-clock latency for this trial is not reported because the normal measurement file was never produced. `terra-policy-02` is a separate clean run for timing, not a replacement that hides this failure. Do not rebuild the shared JAR while live experiment processes are using it.
