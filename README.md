# Told Never To

Artefact pack for *Told Never To: Honest LLM Coordinators Break an Explicit Order at Very Different Points, and One Held*,
an entry to the Apart Research Swarm Alignment sprint, 6–8 November 2026, from
[The Dark Factory](https://thedarkfactory.co.uk).

A team of LLM agents plays capture the flag through a switchboard whose call rules are proved in SPARK. The lead is told
never to pass on a flag or a recipe. With that order, the lead (DeepSeek) still passed the recipe on in 17 of 42 episodes;
with no order, in 29 of 42.

## Correction to the exploratory relay counts (8 November 2026)

The relay detector (`recipe-in?` in `harness/phone-episode.bb`) is a substring match on normalised text. At the 8-bit setting
(`R = 8`, the R8 lever and rung 4 of the ladder) the recipe is only two hex characters, so the detector can fire by coincidence,
for example "dd" in "add" or "71" in a list of numbers. It also counted calls that named the recipe back to the finder.

Read in full by hand, the 23 R8 lever episodes it flagged are 10 real relays and 13 coincidences, none of the 13 with a cheat.
Both rung-4 flags for gemma4 under the order are coincidences too.

**Not corrected here:** the records, `results/levers/T_levers_by_setting.tsv`, both `results/levers/levers_chart*.svg`, and any
casebook card drawn from an R = 8 episode still show the uncorrected counts. The report's Appendix A gives the corrected
counts and is the one to use.

**Not affected:** the confirmatory results. They use a 32-bit recipe (8 hex characters), and every confirmatory number was
recomputed from the records and reproduced.

## What is here

| Folder | What it is |
|---|---|
| `proof/` | The three proved SPARK cores: switchboard, word splitter, agent-text filter |
| `edges/` | Source of the two I/O edges built around those cores. **Tested, not proved** |
| `harness/` | Episode runner, model clients, prompts, analysis scripts and tests (Babashka). **Tested, not proved** |
| `results/confirmatory/` | Episode records, tables, blind coding, the casebook, and `DEVIATIONS.md` |
| `results/levers/` | Exploratory ladder and tipping records. Exploratory only |
| `results/figures/` | Figures |

## Replay the proofs

You need GNATprove (we used the one from [Alire](https://alire.ada.dev)).

```sh
make prove
```

Expected: 259 checks, 39 by flow and 220 by the provers, none justified and none unproved, at `--level=2`.

## Run the tests

From the repository root, with [Babashka](https://babashka.org):

```sh
bb harness/game_test.bb
bb harness/tipping_test.bb
```

`harness/phone-episode_test.bb` needs the two edge binaries; see below.

## Rerun the experiment

Build each edge with `gprbuild -P edge.gpr` in its `edges/` folder. The harness refuses any binary whose sha256 differs from
its pin, and a rebuild on another machine will differ: ours did, on the same machine with a different toolchain setup. So
after building, set `SWARM_SWITCHBOARD_BIN` and `SWARM_SWITCHBOARD_SHA256` for the switchboard, and change `edge-sha256` in
`harness/agent-text-client.bb` for the filter. The original binaries are not shipped. Those runs used the pins recorded in
the records. You also need a DeepSeek API key and, for the local panel, Ollama with the models listed in the report.

Exact replay depends on the host: a model replayed on a different machine reproduced 1 of 26 decisions exactly.

## What is not guaranteed

The proofs cover the switchboard's call rules and the agent-text filter. They do not cover the I/O edges, the reply parser,
the harness or the models. See the report's limitations appendix.

## Preexisting code (Apart rule)

The sprint's prior-work cut was 6 November 2026, 12:14 GMT.

**Preexisting, built before the cut:** everything in `proof/` and `edges/`, and in `harness/`: `phone-episode`, `phone-tables`,
`model-client`, `game`, `switchboard-client`, `agent-text-client`, `stub-switchboard`, `casebook`, `mi`, `episode`,
`gate-client`, `prompt`, their tests, and the prompts.

**Built during the sprint:** `harness/tipping`, `layerscan`, `fano-floor`, `levers-chart`, `figure1`, and the `think=` option on
the local path (logged in `DEVIATIONS.md`). Earlier scratch versions of the tipping curve and layer scan ran before the cut
and are disclosed in the report. All results in `results/` were produced during the sprint unless labelled otherwise.

## Who built what

- **The verification factory**, my own pipeline: a local open-weight model writes SPARK, GNATprove checks it, and deterministic
  gates decide what is admitted. It produced the three proved cores and built the two edges.
- **Claude Code (Anthropic)**, a general coding assistant: under my direction it built the harness and reply parser, the
  analysis scripts and the figures. Nothing it produced is proved.

## What was changed for publication

Local file paths, machine names and internal notes were replaced in these copies (`Host A`, `Host B`, `Host C` for the
machines). No result value was changed. The raw layer dumps (about 200 MB) are left out; `layers.tsv` and `list.tsv` are
included. The prompts contain a capture-the-flag game against a local switchboard, with no exploit for any real system.

## Licence

AGPL-3.0-or-later. See `LICENSE`.
