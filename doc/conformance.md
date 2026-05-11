# Conformance

Hades is exercised against the
[HL7 FHIR Terminology Ecosystem IG](https://github.com/HL7/fhir-tx-ecosystem-ig)
test suite using the upstream `TxTester` harness. The pass count is
pinned to a specific upstream commit so that local and CI runs see the
same test population.

## Running the suite

```shell
clj -X:conformance
```

Per-suite breakdown is printed at the end of every run. Each run's
full result is archived under `test/resources/conformance/` as both a
timestamped JSON file and `latest.json` (the most recent run, always
overwritten). The baseline used to detect regressions is
`test/resources/conformance-baseline.json`.

A normal run does not update the regression baseline. When a new
conformance result is intentional and should become the release gate,
promote it explicitly:

```shell
clj -X:conformance :update-baseline true
```

For a release, the advertised pass count in the README badge, README
prose, and CHANGELOG must match both `latest.json` and
`conformance-baseline.json`. Do not publish a higher pass count while
leaving the baseline behind; that weakens future regression checks.

## REPL-driven workflow

`clj -X:conformance` is fine for one-shot runs but slow when iterating.
The conformance test namespace exposes a REPL API for the
edit→reload→test cycle: lifecycle (start / stop / restart),
test-running (filtered by suite or test name), result inspection,
single-test replay through the live HTTP server, and result
persistence (latest archive, baseline).

Start nREPL with conformance deps:

```shell
clj -M:nrepl:conformance
```

Then require the conformance namespace and drive it from your editor.
See [`doc/development.md`](development.md) for the detailed workflow.

## Pinning the upstream rev

The conformance test namespace pins the upstream tx-ecosystem rev so
CI and local dev see the same test population. Every conformance run
shows the pinned rev and whether upstream main has moved ahead — that
banner is the trigger to consider bumping.

Bumping the pin is a deliberate act: update the rev, re-run
conformance (the harness checks the new rev out), fix any new failures
the updated fixtures surface, then update the conformance baseline
under `test/resources/` and refresh the advertised pass count.
