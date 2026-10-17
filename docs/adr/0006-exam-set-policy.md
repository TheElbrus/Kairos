# ADR 0006: Exam set policy

Date: 2026-10-17
Status: accepted

Decision:
Hand-built map segments are split into dev maps (free to tune on) and exam maps (frozen). Exam maps:
- are run only at gate time,
- are never used to tune the sim, solver, or executor,
- have every run logged (date, commit, result, per-tier score).

Exam maps enter the repo only with the author's permission or a licence that allows it. Map tiers are defined by measurable features (PLAN.md Phase 5, step 2), not by feeling.

Reason:
The previous attempt failed by overfitting to generated maps. A frozen, hand-built exam set is the only honest measure of generalization, and test-set discipline (PLAN.md section 10) is the defense. If the exam set is touched for debugging, its score means nothing.

Alternatives considered:
- One shared pool of maps: rejected; tuning contaminates the score.
- Generated exam maps: rejected as the primary exam; they measure the generator's distribution, not real parkour.
- Random holdout from dev maps: weaker than hand-built frozen maps because dev maps are a narrow distribution.

Consequences:
- Exam failures are triaged into exactly one category (A sim mismatch, B missing block/shape/property, C planner limit, D executor/timing, E out of scope) and logged in `docs/FAILURES.md`.
- New maps go to dev first. Exam maps are added only as a set, before a baseline run.
- Gate 5 thresholds are set after the first baseline and recorded in `gates/phase5.json`.
