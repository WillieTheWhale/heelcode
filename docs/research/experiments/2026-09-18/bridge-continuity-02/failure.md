# Real classifier validation failure

The second continuity run completed the initial answer, then stopped at the first recall request. Luna returned `activities: []`, `unclear: false`, and `dishonest: false`, explaining that recall of a label did not request categorized academic assistance. This violated the existing classifier invariant. The validator failed closed with `forward:false`; no second answer-model call was made.

This is a real model/prompt-contract failure, not the test-runner field mismatch from `bridge-continuity-01`. It also shows why the earlier 60/60 synthetic decision results are not a general reliability claim.

The revised extraction instructions explicitly categorize simple conversation bookkeeping as logistics, retain substantive categories for repeated code/tests/solutions/prose, and require `unclear:true` when no category can be resolved. Validation is not weakened and there is no automatic allow fallback. The revised prompt is evaluated in new directories, including the original Luna cases and a new eight-case bookkeeping regression set authored after observing this failure. Historical Luna/Terra comparison artifacts still describe the earlier prompt.
