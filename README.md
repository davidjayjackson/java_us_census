# java_us_census

Task: Build a LibreOffice Calc Add-In (UNO component) in Java that exposes US Census Bureau
data as worksheet functions, packaged as a deployable `.oxt` extension.

Environment & constraints:

- Target: LibreOffice Calc 24.8+ on Windows, LibreOffice SDK installed.
- Language: Java, using `com.sun.star.sheet.AddIn` so functions are callable as `=CENSUS_GET(...)` etc.
- Prefer only JDK standard library for HTTP/JSON — no third-party jars.
- Calc formula args use semicolons as separators (e.g. `=CENSUS_GET(2022; "acs/acs5"; "NAME,B01003_001E"; "state:*")`).

Census API:

- Base: `https://api.census.gov/data`. Requests take the form
  `https://api.census.gov/data/{year}/{dataset}?get={variables}&for={geography}&key={API_KEY}`.
- The key is optional for most low-volume, non-commercial use — never hardcoded; read from an
  environment variable, a function argument, or a config file.
- Response format is a JSON array where the first row is headers and subsequent rows are data.

Functions implemented:

- `CENSUS_GET(year; dataset; variables; geography)` — core query, returns the full result table.
- `CENSUS_VALUE(year; dataset; variable; geography)` — single scalar value.
- `CENSUS_VARLABEL(year; dataset; variable)` — human-readable label for a variable code.
- `CENSUS_DATASETS(year)` — list available datasets for a year.

See [docs/FUNCTIONS.md](docs/FUNCTIONS.md) for the complete reference (every argument,
return shape, error conditions, and worked examples for all four functions).

---

## Implementation

A Java UNO add-in (`com.sun.star.sheet.AddIn`), packaged as `build/Census.oxt`. Built,
installed, and **verified end-to-end** against live Census data (2022 ACS 5-year estimates)
on LibreOffice 26.2 (Linux dev machine; targets Windows per the spec above).

### Layout

| Path | Purpose |
|------|---------|
| `idl/com/example/census/XCensus.idl` | Custom UNO interface (the 4 functions) |
| `src/com/example/census/CensusImpl.java` | The add-in: `XCensus` + `XAddIn` + `XServiceName`/`XServiceInfo`, display↔programmatic name mapping, UNO registration |
| `src/com/example/census/CensusClient.java` | `HttpURLConnection` client + per-session response cache; API key from the `api_key` argument, `CENSUS_API_KEY` env var, or omitted |
| `src/com/example/census/Json.java` | Hand-rolled JSON parser (no third-party jars) |
| `registration/CalcAddIns.xcu` | Function display names, descriptions, argument help |
| `registration/{manifest,description}.xml`, `MANIFEST.MF` | `.oxt` manifest, extension metadata, jar `RegistrationClassName` |
| `build.sh` / `build.ps1` | `unoidl-write` → `javamaker` → `javac` (Java 8) → `jar` → zip `.oxt` |
| `tools/test_census.py` | Headless end-to-end test (all 4 functions + error paths) |
| `tools/build_demo.py` | Regenerates the demo spreadsheet |
| `demo/Census-Demo.ods` | Demo spreadsheet with live formulas and computed results |
| `docs/INSTALL.md` | Full build / install / run instructions |
| `docs/FUNCTIONS.md` | Complete function reference: every argument, return shape, errors, examples |

### Quick start (build from source)

```bash
export CENSUS_API_KEY='your_key'          # optional, never hardcoded
./build.sh --libreoffice ~/libreoffice26.2 --jdk ~/jdks/jdk8u492-b09
"$LO_HOME/program/unopkg" add --force build/Census.oxt
# then launch LibreOffice from an environment where CENSUS_API_KEY is set (if using one)
```

Windows: `pwsh -File build.ps1` (see `docs/INSTALL.md` for prerequisites and the Java-vendor
allow-list note).

### Demo

`demo/Census-Demo.ods` is a real spreadsheet with `CENSUS_*` formulas already entered and
computed against live Census data — open it to see the functions working without typing
anything (it needs the add-in installed first, see above, or the formulas show `#NAME?`).
It covers all four functions: scalar `CENSUS_VALUE`/`CENSUS_VARLABEL` calls, a compact
`CENSUS_GET` array formula for three states, a full `CENSUS_GET` array formula using
`in_geography` (every California county), and a `CENSUS_DATASETS` slice.

It was generated with `tools/build_demo.py`, which drives a headless Calc instance over the
UNO API to enter the formulas, recalculate, and save the file — regenerate it after changing
the add-in with:

```bash
export CENSUS_API_KEY='your_key'   # optional
"$LO_HOME/program/soffice" --headless --norestore --accept="socket,host=localhost,port=2002;urp;" &
"$LO_HOME/program/python" tools/build_demo.py
```

Key implementation notes:

- **Runtime**: compiled to Java 8 bytecode using `java.net.HttpURLConnection` (both JDK
  standard library), so it runs on the JRE LibreOffice accepts by default — no runtime JRE
  reconfiguration needed. (`java.net.http.HttpClient` would need Java 11+; see `docs/INSTALL.md`
  for why this project targets Java 8 instead, matching the other add-ins in this environment.)
- **API key is optional**: the Census API allows keyless requests for low-volume,
  non-commercial use. Every data-fetching function takes an optional trailing `api_key`
  argument (type it, or reference a cell); when omitted, the `CENSUS_API_KEY` environment
  variable is used if set, and otherwise the request is simply sent without a key.
- **Geography**: `geography` maps to the API's `for=` clause (e.g. `"state:*"`, `"county:*"`).
  An optional trailing `in_geography` argument maps to `in=` (e.g. `"state:06"`), needed for
  sub-state geographies like county or tract.
- **Array output**: LibreOffice has no dynamic spill — select the output range and enter
  `CENSUS_GET` / `CENSUS_DATASETS` as an array formula (Ctrl+Shift+Enter, or tick *Array* in
  the Function Wizard).
- **Minimal parsing**: `CENSUS_GET` returns every cell as text, matching the API's own
  JSON-array-of-arrays shape. `CENSUS_VALUE` additionally coerces numeric-looking values to
  numbers so they're directly usable in formulas, and a null value becomes an empty cell.
- **Errors** (bad dataset/variable/year, missing data, network failure) surface as Calc error
  values (`Err:502`), not exception strings.
- Responses are cached per session (keyed by full request URL), so recalculation does not
  re-hit the API.

### Verified results

```
=CENSUS_VALUE(2022; "acs/acs5"; "NAME"; "state:06")                    -> California
=CENSUS_VALUE(2022; "acs/acs5"; "B01003_001E"; "state:06")             -> 39356104
=CENSUS_VARLABEL(2022; "acs/acs5"; "B01003_001E")                      -> Estimate!!Total
=CENSUS_GET(2022; "acs/acs5"; "NAME,B01003_001E"; "state:*")           -> 52 rows (name, population)
=CENSUS_DATASETS(2022)                                                 -> spills (Dataset, Title) rows
```

### License

Released under the [MIT License](LICENSE).
