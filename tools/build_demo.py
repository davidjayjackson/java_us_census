"""Generate demo/Census-Demo.ods using the installed Census add-in.

Run against a headless LibreOffice (with CENSUS_API_KEY in its environment,
optional) that is listening on a UNO socket; see docs/INSTALL.md. Produces a
spreadsheet whose cells contain live CENSUS_* formulas (scalars plus real
multi-cell array formulas for CENSUS_GET and CENSUS_DATASETS), with values
already computed against the live Census API.
"""
import os
import sys
import time
import uno
from com.sun.star.beans import PropertyValue


def connect(port=2002, tries=60):
    local = uno.getComponentContext()
    resolver = local.ServiceManager.createInstanceWithContext(
        "com.sun.star.bridge.UnoUrlResolver", local)
    url = "uno:socket,host=localhost,port=%d;urp;StarOffice.ComponentContext" % port
    last = None
    for _ in range(tries):
        try:
            return resolver.resolve(url)
        except Exception as e:
            last = e
            time.sleep(0.5)
    raise SystemExit("could not connect to LibreOffice: %s" % last)


def main():
    out_path = os.path.abspath(
        os.path.join(os.path.dirname(__file__), "..", "demo", "Census-Demo.ods"))
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    out_url = uno.systemPathToFileUrl(out_path)

    ctx = connect()
    smgr = ctx.ServiceManager
    desktop = smgr.createInstanceWithContext("com.sun.star.frame.Desktop", ctx)
    doc = desktop.loadComponentFromURL("private:factory/scalc", "_blank", 0, ())
    try:
        sh = doc.Sheets.getByIndex(0)
        sh.Name = "Census Demo"

        def put(col, row, value):
            sh.getCellByPosition(col, row).setString(value)

        def formula(col, row, f):
            sh.getCellByPosition(col, row).setFormula(f)

        put(0, 0, "US Census Calc Add-In - demo")
        put(0, 1, "CENSUS_API_KEY is optional; set it, launch LibreOffice, recalc with Ctrl+Shift+F9.")
        put(0, 2, 'Or pass the key per formula (last arg), e.g. =CENSUS_VALUE(2022;"acs/acs5";"B01003_001E";"state:06";"";$B$1).')

        put(0, 3, "Function")
        put(1, 3, "Live result")
        put(2, 3, "Formula")

        rows = [
            ("CENSUS_VALUE NAME (California)", '=CENSUS_VALUE(2022;"acs/acs5";"NAME";"state:06")'),
            ("CENSUS_VALUE population (California)", '=CENSUS_VALUE(2022;"acs/acs5";"B01003_001E";"state:06")'),
            ("CENSUS_VARLABEL (population variable)", '=CENSUS_VARLABEL(2022;"acs/acs5";"B01003_001E")'),
        ]
        r = 4
        for label, f in rows:
            put(0, r, label)
            formula(1, r, f)
            put(2, r, f)
            r += 1

        # CENSUS_GET as a real multi-cell array formula: NAME + population for
        # three specific states (comma-separated FIPS codes in the "for" clause).
        r += 1
        put(0, r, "CENSUS_GET (array formula, NAME + population for CA/NY/TX)")
        r += 1
        first = r  # header row is the first row of the array
        rng = sh.getCellRangeByPosition(0, first, 1, first + 3)  # A..B, 4 rows
        get_f = '=CENSUS_GET(2022;"acs/acs5";"NAME,B01003_001E";"state:06,36,48")'
        rng.setArrayFormula(get_f)
        put(2, first, "{" + get_f + "}  (select range, Ctrl+Shift+Enter)")

        # CENSUS_GET with in_geography as a real multi-cell array formula:
        # NAME + population for every county in California (58 counties + header).
        r = first + 4 + 1  # skip the 4-row CENSUS_GET block and one blank row
        put(0, r, "CENSUS_GET with in_geography (array formula, every California county)")
        r += 1
        cfirst = r
        crng = sh.getCellRangeByPosition(0, cfirst, 1, cfirst + 58)  # A..B, 59 rows
        county_f = '=CENSUS_GET(2022;"acs/acs5";"NAME,B01003_001E";"county:*";"state:06")'
        crng.setArrayFormula(county_f)
        put(2, cfirst, "{" + county_f + "}  (select range, Ctrl+Shift+Enter)")

        # CENSUS_DATASETS as a real multi-cell array formula: first 15 datasets
        # available for 2022 (the full list has ~99; this is a representative slice).
        r = cfirst + 59 + 1  # skip the 59-row county block and one blank row
        put(0, r, "CENSUS_DATASETS (array formula, first 15 datasets for 2022)")
        r += 1
        dfirst = r
        drng = sh.getCellRangeByPosition(0, dfirst, 1, dfirst + 15)  # A..B, 16 rows
        ds_f = '=CENSUS_DATASETS(2022)'
        drng.setArrayFormula(ds_f)
        put(2, dfirst, "{" + ds_f + "}  (select range, Ctrl+Shift+Enter)")

        # Widen columns a little for readability.
        cols = sh.Columns
        cols.getByIndex(0).Width = 9000
        cols.getByIndex(1).Width = 6500
        cols.getByIndex(2).Width = 12000

        doc.calculateAll()

        # Save as ODF spreadsheet (calc8 = .ods).
        fn = PropertyValue()
        fn.Name = "FilterName"
        fn.Value = "calc8"
        doc.storeToURL(out_url, (fn,))
        print("wrote", out_path)
    finally:
        doc.close(False)
        desktop.terminate()


if __name__ == "__main__":
    main()
