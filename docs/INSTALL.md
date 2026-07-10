# US Census Calc Add-In — build & install

A LibreOffice Calc add-in (UNO component, **Java**) exposing US Census Bureau
data as worksheet formulas:

| Function | Signature | Returns |
|----------|-----------|---------|
| `CENSUS_GET`      | `CENSUS_GET(year; dataset; variables; geography; [in_geography]; [api_key])` | spillable array: header row + one row per geography, all values as text |
| `CENSUS_VALUE`    | `CENSUS_VALUE(year; dataset; variable; geography; [in_geography]; [api_key])` | a single value (numeric when possible, else text) |
| `CENSUS_VARLABEL` | `CENSUS_VARLABEL(year; dataset; variable)`                     | the variable's human-readable label |
| `CENSUS_DATASETS` | `CENSUS_DATASETS(year)`                                        | spillable `(Dataset, Title)` array of datasets available that year |

> In Calc's UI, arguments are separated by **semicolons**:
> `=CENSUS_GET(2022; "acs/acs5"; "NAME,B01003_001E"; "state:*")`.
>
> `CENSUS_GET` and `CENSUS_VALUE` take two optional trailing arguments: **`in_geography`**
> (the API's `in=` clause, e.g. `"state:06"`, needed for sub-state geographies) and
> **`api_key`** (overrides the environment variable, typed literally or as a cell reference).

---

## 1. Prerequisites

### Windows

1. **LibreOffice + SDK.** Default install path
   `C:\Program Files\LibreOffice`, with the SDK under `…\LibreOffice\sdk`.
   The SDK provides `sdk\bin\unoidl-write.exe` and `sdk\bin\javamaker.exe`.
2. **A JDK to build with** — any JDK 8 or newer (`javac`, `jar`). The build
   targets **Java 8 bytecode** (`--release 8`), so the add-in runs on the
   Oracle JRE 8 that LibreOffice accepts out of the box. Pass its path with
   `-Jdk`, or set `JAVA_HOME`, or put `javac` on `PATH`.
3. **Runtime JRE — nothing to change.** The component is Java-8 bytecode and
   uses only `java.net.HttpURLConnection`, so LibreOffice's existing/default
   Oracle JRE (8+) runs it as-is. No *Tools ▸ Options ▸ Advanced* change needed.

> Why not `java.net.http.HttpClient`? That needs Java 11+, but a LibreOffice
> install's `javavendors.xml` allow-list typically only accepts Oracle/Sun/IBM/
> Azul/Amazon by default — modern non-Oracle JDKs (e.g. Temurin, JetBrains JBR)
> may be rejected until added to that list. Targeting Java 8 +
> `HttpURLConnection` (both JDK standard library) avoids installing a new JRE
> while still meeting the "JDK standard library only" requirement.

Confirm the tools resolve:

```powershell
& 'C:\Program Files\LibreOffice\sdk\bin\unoidl-write.exe' --help
& 'C:\Program Files\LibreOffice\sdk\bin\javamaker.exe'    # prints usage
javac -version   # any 8+
```

### Linux / macOS

1. **A JDK 8** (`javac`, `jar`). If your distro's package manager has one
   (`apt install openjdk-8-jdk-headless`, `dnf install java-1.8.0-openjdk-devel`,
   …) use that. Otherwise, no root needed — fetch a JDK 8 build straight from
   Eclipse Adoptium/Temurin and unpack it under your home directory:

   ```bash
   curl -s "https://api.adoptium.net/v3/assets/latest/8/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse" \
     | grep -o '"link": *"[^"]*tar.gz"' | head -1 | cut -d'"' -f4
   # download that URL, then:
   mkdir -p ~/jdks && tar xzf OpenJDK8U-jdk_x64_linux_hotspot_*.tar.gz -C ~/jdks
   export JAVA_HOME=~/jdks/jdk8u<version>   # match the extracted directory name
   export PATH="$JAVA_HOME/bin:$PATH"
   ```

2. **LibreOffice + SDK.** If your distro packages a matching SDK
   (`apt install libreoffice-dev libreoffice-dev-common` on Debian/Ubuntu),
   use that and skip to confirming the tools below. Otherwise, download the
   generic Linux tarballs from
   <https://download.documentfoundation.org/libreoffice/stable/> (the
   `_rpm.tar.gz` and matching `_rpm_sdk.tar.gz` for your version/arch) and
   extract each `.rpm` inside them with `rpm2cpio`/`cpio` into a prefix
   directory — no root required:

   ```bash
   tar xzf LibreOffice_*_rpm.tar.gz LibreOffice_*_rpm_sdk.tar.gz
   mkdir -p ~/opt
   for rpm in LibreOffice_*_rpm/RPMS/*.rpm LibreOffice_*_rpm_sdk/RPMS/*.rpm; do
     rpm2cpio "$rpm" | cpio -idm --no-absolute-filenames -D ~/opt
   done
   mv ~/opt/opt/libreoffice* ~/libreoffice26.2   # adjust to the extracted version
   export LO_HOME=~/libreoffice26.2
   ```

   This lays out the same `program/` and `sdk/bin/` tree the Windows install
   has (`unoidl-write`, `javamaker`, `types.rdb`, `program/classes/*.jar`).

3. **Java vendor allow-list.** LibreOffice only loads a JVM whose
   `java.vendor` appears in `$LO_HOME/program/javavendors.xml` (Sun, Oracle,
   IBM, Blackdown, BEA, Azul, Amazon by default). A stock Temurin/Adoptium
   build reports vendor `Temurin`, which is **not** on that list by default —
   `unopkg` will fail with `CannotRegisterImplementationException: Could not
   create Java implementation loader` when installing the extension. Add an
   entry for it in your local `javavendors.xml` (this file lives inside your
   own LibreOffice install, not a system-shared one, so editing it is safe):

   ```xml
   <vendor name="Temurin">
     <minVersion>1.8.0</minVersion>
   </vendor>
   ```

   Insert it next to the other `<vendor>` entries, inside `<vendorInfos>`.
   (If your JDK came from your distro's package manager, its vendor is
   usually already on the list and this step is unnecessary.)

Confirm the tools resolve:

```bash
"$LO_HOME/sdk/bin/unoidl-write"          # prints usage
"$LO_HOME/sdk/bin/javamaker"             # prints usage
"$JAVA_HOME/bin/javac" -version          # any 8+
```

## 2. Provide the Census API key (optional, never hardcoded)

The Census API allows **keyless** requests for low-volume, non-commercial use, so this
step is optional — but a key raises your rate limit. Get a free one at
<https://api.census.gov/data/key_signup.html>.

Three ways, in priority order:

1. **The `api_key` function argument** — the optional trailing argument of `CENSUS_GET`
   and `CENSUS_VALUE`. Type it literally, or (recommended) put it in one cell and
   reference that cell: `=CENSUS_VALUE(2022; "acs/acs5"; "B01003_001E"; "state:06"; ""; $B$1)`.
   Wins when supplied.
2. **The `CENSUS_API_KEY` environment variable** — used whenever the argument is omitted.
3. **Neither** — the request is sent without a key at all.

The key is never hardcoded in the add-in. Set it **for the LibreOffice process** — i.e. set
it, then launch `soffice` from that same shell (or set it as a persistent user env var and
restart LibreOffice):

```powershell
$env:CENSUS_API_KEY = 'your_40_char_key'
& 'C:\Program Files\LibreOffice\program\soffice.exe'
```

Persistent (survives reboots; restart LibreOffice afterwards):

```powershell
setx CENSUS_API_KEY "your_40_char_key"
```

Linux / macOS — same idea, `export` instead of `$env:`/`setx`:

```bash
export CENSUS_API_KEY='your_40_char_key'
"$LO_HOME/program/soffice"
```

Persistent: add the `export` line to `~/.bashrc` (or `~/.zshrc`), then restart your shell
and LibreOffice.

## 3. Build the .oxt

### Windows

From the project root:

```powershell
# JAVA_HOME set, or javac on PATH:
pwsh -File build.ps1
# or point at a specific JDK:
pwsh -File build.ps1 -Jdk 'C:\Program Files\Java\jdk-11'
```

This runs the full pipeline and produces **`build\Census.oxt`**:

```
1. unoidl-write  idl\**                -> build\types\XCensus.rdb
2. javamaker     build\types\XCensus.rdb -> build\gen\**.class
3. javac         src\**.java           -> build\classes\**.class
4. jar           classes + bindings    -> build\oxt\census.jar
5. zip           staging tree          -> build\Census.oxt
```

### Linux / macOS

```bash
export JAVA_HOME=~/jdks/jdk8u<version>   # or wherever your JDK 8 lives
export LO_HOME=~/libreoffice26.2         # or wherever LibreOffice + SDK live
./build.sh
# or pass paths explicitly instead of the env vars:
./build.sh --jdk ~/jdks/jdk8u<version> --libreoffice ~/libreoffice26.2
```

This produces `build/Census.oxt` via the same five steps. Two JDK-8-specific quirks it
works around, in case you're compiling by hand:

- **`javac --release 8` doesn't exist on JDK 8 itself** (the flag was added in JDK 9);
  `build.sh` detects a `1.x` `javac -version` and falls back to `-source 8 -target 8`,
  which is equivalent for a straight JDK-8 build.
- **`jar` on JDK 8 can reject duplicate directory entries** when packaging two class trees
  that share a package path; `build.sh` merges both trees into one staging directory
  first, then jars that single tree.

## 4. Install into LibreOffice

Close LibreOffice first, then use `unopkg` from the program dir:

```powershell
& 'C:\Program Files\LibreOffice\program\unopkg.exe' add --force build\Census.oxt
# list / remove:
& 'C:\Program Files\LibreOffice\program\unopkg.exe' list
& 'C:\Program Files\LibreOffice\program\unopkg.exe' remove com.example.census
```

You can also install by double-clicking `build\Census.oxt` (opens the Extension Manager).
After installing, **restart LibreOffice** from a shell that has `CENSUS_API_KEY` set, if
you're using one (step 2).

Linux / macOS:

```bash
"$LO_HOME/program/unopkg" add --force build/Census.oxt
# list / remove:
"$LO_HOME/program/unopkg" list
"$LO_HOME/program/unopkg" remove com.example.census
```

## 5. Try it

In any sheet:

```
=CENSUS_VALUE(2022; "acs/acs5"; "NAME"; "state:06")                   -> California
=CENSUS_VALUE(2022; "acs/acs5"; "B01003_001E"; "state:06")            -> 39356104
=CENSUS_VARLABEL(2022; "acs/acs5"; "B01003_001E")                     -> Estimate!!Total
=CENSUS_DATASETS(2022)                                                 -> spills (Dataset, Title) rows
```

For `CENSUS_GET`, select an output range large enough for the result, type the formula,
and confirm with **Ctrl+Shift+Enter** (array formula) — LibreOffice has no dynamic spill:

```
=CENSUS_GET(2022; "acs/acs5"; "NAME,B01003_001E"; "state:*")          -> 52 rows: name, population
```

For a county within a state (needs the `in_geography` argument):

```
=CENSUS_GET(2022; "acs/acs5"; "NAME,B01003_001E"; "county:*"; "state:06")  -> California's counties
```

## Behavior notes

- **Errors → Calc error values.** Bad datasets/variables/years, no data for a geography, or
  a network failure raise a UNO exception, which Calc shows as an error value (e.g.
  `Err:502`) in the cell — not an exception string.
- **Multi-cell / spilling.** To get all rows of `CENSUS_GET` or `CENSUS_DATASETS`, select the
  output range and enter it as an **array formula** — Ctrl+Shift+Enter, or tick **Array** in
  the Function Wizard. A single-cell entry shows only the first value.
- **Geography.** `geography` is the API's `for=` clause (e.g. `"state:*"`, `"county:*"`,
  `"state:06"`); the optional `in_geography` argument is the `in=` clause (e.g. `"state:06"`),
  needed whenever `geography` is below the state level.
- **`CENSUS_VALUE` expects one specific geography** (not a wildcard) — it returns the first
  data row's value. Use `CENSUS_GET` with a wildcard to pull every geography at once.
- **Minimal parsing.** `CENSUS_GET` returns every cell as text, matching the API's own
  JSON-array-of-arrays shape. `CENSUS_VALUE` coerces numeric-looking values to numbers; a
  null value becomes an empty cell.
- **Per-session caching.** Every raw response is cached by request URL for the life of the
  office session, so recalculation does not re-hit the API. Restart LibreOffice to clear
  the cache.
- **No third-party jars.** HTTP uses `java.net.HttpURLConnection`; JSON is parsed by a small
  hand-rolled parser (`Json.java`). Nothing beyond the JDK + UNO is bundled.

## Automated test

`tools/test_census.py` drives a headless LibreOffice over a UNO socket and checks all four
functions plus the error paths against live Census data:

```powershell
$env:CENSUS_API_KEY = 'your_key'   # optional
& 'C:\Program Files\LibreOffice\program\soffice.exe' --headless --norestore --accept="socket,host=localhost,port=2002;urp;"
& 'C:\Program Files\LibreOffice\program\python.exe' tools\test_census.py   # prints RESULT: PASS
```

Linux / macOS — same idea, LibreOffice's bundled `python` (it ships the `uno` module)
instead of `python.exe`, run in the background so the shell isn't blocked:

```bash
export CENSUS_API_KEY='your_key'   # optional
"$LO_HOME/program/soffice" --headless --norestore --accept="socket,host=localhost,port=2002;urp;" &
"$LO_HOME/program/python" tools/test_census.py   # prints RESULT: PASS
```

## Troubleshooting

- `unoidl-write` / `javamaker` "not found" → pass the right `--libreoffice`/`-LibreOffice`
  path; the SDK must be installed (it is a separate download from LibreOffice).
- Functions show `#NAME?` → the extension isn't registered; confirm with `unopkg list` and
  restart LibreOffice.
- Every Census cell is an error value (`Err:502`) → check the dataset/variable/year are
  valid, or that geography syntax is correct (e.g. `"state:*"`, not `"states:*"`).
- `unopkg add` fails with `CannotRegisterImplementationException: Could not create Java
  implementation loader` (Linux/macOS) → your JDK's vendor isn't in
  `$LO_HOME/program/javavendors.xml`'s allow-list — see the Java vendor allow-list note in
  Prerequisites.
