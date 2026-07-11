# Changelog

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
