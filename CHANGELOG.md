# Changelog

## 1.0.0

- Initial release: `CENSUS_GET`, `CENSUS_VALUE`, `CENSUS_VARLABEL`, `CENSUS_DATASETS`
  worksheet functions against the US Census Bureau data API.
- Java 8 / `HttpURLConnection` / hand-rolled JSON parser, no third-party jars.
- Optional `api_key` argument, falling back to the `CENSUS_API_KEY` environment
  variable, falling back to a keyless request.
- Per-session response caching keyed by request URL.
- Verified end-to-end against live 2022 ACS 5-year data on LibreOffice 26.2.
