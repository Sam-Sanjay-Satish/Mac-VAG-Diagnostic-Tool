# labels

Layer guide. Read the root `CLAUDE.md` first (especially "Label files" and "Car data and privacy").

**Status:** built 2026-11-19 for v2 live data. 19 tests, `./gradlew :labels:build` green. **Package:** `io.github.samsanjaysatish.macvagdiag.labels`
**Depends on:** nothing but SLF4J (plain file parsing: no bus, no protocol). **Used by:** `scan` (`implementation`; no labels type appears in scan's public API).

## Purpose
Finds a module's label file the way VCDS does and reads its **measuring-block** lines: group titles, field names, and the help lines that are ranges or specifications. Everything else in a label file (basic settings, adaptation, coding, security access, output tests, other help text) is ignored, so it can never reach the catalog or the AI.

## Public API
- `LabelLibrary.of(List<Path> folders)` (searched in order; a missing folder is skipped with a warning), `LabelLibrary.ofTexts(Map<name, text>)` (files given as text: demo mode's bundled made-up labels), `LabelLibrary.empty()`.
- `LabelLookup lookup(int address, String partNumber, Optional<String> hardwarePartNumber)` → `LabelLookup(Optional<MeasuringLabels> labels, List<String> trail)`, `source()`. "No label file" is a normal result.
- `MeasuringLabels(source, groups, withheldGroups)`, `GroupLabel(group, title, fields)`, `FieldLabel(field, name, detail, specs)` with `displayName()` (`Coolant Temperature (G62)`), `PartNumber.parse` (`03L997030M  ` / `03L 997 030 M` / `03L-997-030-M`).

## Lookup (Ross-Tech's documented order)
- Per folder, by file name (case ignored): `TEST-NN`, the full part number with suffix, the root without suffix, `AA-NN` (first two characters of the part number + module address in hex). `.lbl` and `.clb` names are the same file (the decoded copy keeps `X.clb` as `X.lbl`).
- **Hardware part number fallback:** if nothing is found for the software part number, the same search runs with the hardware part number. VCDS does this for the target car's DSG (SW `02E 300 062 B` → labels `02E-927-770` from HW `02E 927 770 AL`).
- **Redirects** (first matching line wins, up to 4 hops, target searched in every folder; missing target or no match → the file itself):
  - suffix table `REDIRECT,<file>,AF,AQ` (text after `;` is a comment);
  - pattern `REDIRECT,<file>,03L-997-030-M` with `?` wildcards; a pattern longer than the part number matches only if the rest is `?`/`-`;
  - `REDIRECT,<file>,<pattern>,HW`: matched against the hardware part number. Other qualifiers (`SL`, subsystems) are skipped.
  - A bare `REDIRECT,<file>` line is ignored: the only one in our set (`03-01.lbl` line 48) is a truncated line, and VCDS ignores it too.
- **Files:** Windows-1252 text. Measuring lines are `GGG,0,Title` and `GGG,F,Name,Detail,Help`, group 1–4 digits (1..255), fields 1..4 (above 4 ignored), help lines separated by a literal `\n`, no CSV quoting (a comma in a name splits it). Only help lines starting with `Range` or `Specification` are kept (user's decision 2026-11-19: names and specs go to the AI).

## Privacy (user's decision 2026-11-19)
`PrivacyFilter` withholds a whole group if its title, a field name or detail says VIN, vehicle identification, chassis number, immobilizer ID / IMMO-ID, WFS-ID, key ID / identity. Withheld groups are not in `groups()`; only their numbers are kept. Checked against all 1,964 decoded files: catches every "Vehicle Ident. Number (VIN)" and "IMMO-ID" (e.g. the target engine's group 081, which holds both) and the key identities. Groups that only *mention* the immobilizer (CAN communication status listing it as a node: ABS 126/226, gateway 128), key counts, key numbers and transponder status stay readable.

## Checked against the real label set (2026-11-19, local only)
For the target car, the lookup lands on the file VCDS's 2026-11-18 Auto-Scan names for every KWP module: 01 `03L-906-022-CBE` (via `03-01.lbl`'s pattern; 103 groups, 081 withheld), 02 `02E-927-770` (HW fallback), 03 `1K0-907-379-EC1-V3` (via `1K-03`), 08 `5C0-820-047`, 19 `6N0-909-901-6R` (via `6N-19`), 44 `1K0-909-14x-GEN3` (via `1K-44`). Engine groups 001, 007, 011, 099, 100 have the same names as the columns of VCDS's CSVs from the v2 sniffing session.
- **Known difference:** 09 (BCM `5C0 937 087 E`): VCDS uses `6R0-937-08x-09`, but no file in our set redirects there (VCDS's own mapping), so we have no labels for 09; its groups are found by probing (see `scan`).
- UDS modules (05, 15, 17, 25, 2B, 77) also find files, but they don't matter: their live values are DIDs, not measuring groups (not built).

## Tests
Hand-written, made-up label files in `src/test/resources/labels/` (the real set must stay out of git): `LabelLibraryTest` (search order, folders in order, suffix table with a `.clb` target, `?` patterns, `HW` patterns, HW fallback, the bare redirect, redirect loops, missing targets, "none", part numbers, in-memory files) and `LabelFileTest` (names, specs, CP-1252 `°`, bit tables dropped, B/A/C/LC/S/T lines never groups, odd shapes, privacy).

## Open questions
- Whether the label set may be committed or redistributed (root `CLAUDE.md`): still open; nothing from it is in git.
- 09's labels (VCDS's internal mapping, above).
- The `.clb`/`.crd` originals aren't read; we read the decoded copy. How a user would decode their own set is open.
