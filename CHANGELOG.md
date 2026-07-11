# Changelog

## 1.2.1

- **Correction**: the Census API no longer honors keyless low-volume requests for
  `CENSUS_GET`/`CENSUS_VALUE` -- a keyless data request now redirects to an HTML
  "missing key" page. Docs (README, INSTALL.md, FUNCTIONS.md/.pdf) updated to state
  the key is required for those two functions; `CENSUS_VARLABEL`/`CENSUS_DATASETS`
  still genuinely need no key. Found and verified live while checking the v1.2.0
  release install end to end.
- `CensusClient` now detects that redirect (via the API's `X-DataWebAPI-KeyError`
  header) and raises a clear "Census API key required" error instead of failing
  later with a confusing JSON-parse error. Also detects an HTML response more
  generally as a fallback.

## 1.2.0

- Add `docs/FUNCTIONS.md`, a complete reference for all four functions (every
  argument, return shape, error conditions, and examples), with every formula
  verified live against the API.
- Add `docs/FUNCTIONS.pdf`, a styled, paginated render of the same reference
  for offline/print use.

## 1.1.0

- Add `demo/Census-Demo.ods`, a spreadsheet with live `CENSUS_*` formulas already
  computed against the API, covering all four functions including `in_geography`.
- Add `tools/build_demo.py` to regenerate the demo via headless UNO automation.

## 1.0.0

- Initial release: `CENSUS_GET`, `CENSUS_VALUE`, `CENSUS_VARLABEL`, `CENSUS_DATASETS`
  worksheet functions against the US Census Bureau data API.
- Java 8 / `HttpURLConnection` / hand-rolled JSON parser, no third-party jars.
- Optional `api_key` argument, falling back to the `CENSUS_API_KEY` environment
  variable, falling back to a keyless request.
- Per-session response caching keyed by request URL.
- Verified end-to-end against live 2022 ACS 5-year data on LibreOffice 26.2.
