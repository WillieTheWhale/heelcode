# HeelCode system paper (draft, November 5, 2026)

`paper.tex` describes the architecture, the Java desktop app, and every evaluation to date: the September 18 policy-generation and classifier comparisons and the November 5 enforcement sweep, live red-team runs, live policy generation, and end-to-end app session.

Build (Tectonic fetches packages on first use):

```sh
cd docs/research/heelcode-system-paper
tectonic -X compile paper.tex --outdir ../../../output/pdf
mv ../../../output/pdf/paper.pdf ../../../output/pdf/heelcode-system-paper.pdf
```

`references.bib` is a copy of the education-policy review's verified bibliography. The figures are renders of the real app from scripted sessions (`heelcode policy gui --snapshot …`, see the policy-engine README). The underlying evidence is in [`../experiments/2026-09-18`](../experiments/2026-09-18) and [`../experiments/2026-11-05`](../experiments/2026-11-05).
