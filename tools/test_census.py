"""End-to-end test for the US Census Calc add-in.

Run with LibreOffice's bundled Python (it ships the `uno` module) against a
headless instance listening on a UNO socket:

    export CENSUS_API_KEY=...   (optional -- must be visible to the soffice process)
    soffice --headless --norestore --accept="socket,host=localhost,port=2002;urp;"
    "$LO_HOME/program/python" tools/test_census.py

Exercises CENSUS_GET, CENSUS_VALUE, CENSUS_VARLABEL and CENSUS_DATASETS
against live 2022 ACS 5-year data. Prints RESULT: PASS / FAIL and exits
non-zero on failure.
"""
import sys
import time
import uno


def connect(port=2002, tries=60):
    local = uno.getComponentContext()
    resolver = local.ServiceManager.createInstanceWithContext(
        "com.sun.star.bridge.UnoUrlResolver", local)
    url = "uno:socket,host=localhost,port=%d;urp;StarOffice.ComponentContext" % port
    last = None
    for _ in range(tries):
        try:
            return resolver.resolve(url)
        except Exception as e:  # not yet listening
            last = e
            time.sleep(0.5)
    raise SystemExit("could not connect to LibreOffice: %s" % last)


def main():
    ctx = connect()
    smgr = ctx.ServiceManager
    desktop = smgr.createInstanceWithContext("com.sun.star.frame.Desktop", ctx)
    doc = desktop.loadComponentFromURL("private:factory/scalc", "_blank", 0, ())
    results = {}
    try:
        sheet = doc.Sheets.getByIndex(0)

        # --- scalar functions ---------------------------------------------
        c_name = sheet.getCellByPosition(0, 0)   # A1: California's NAME
        c_name.setFormula('=CENSUS_VALUE(2022;"acs/acs5";"NAME";"state:06")')

        c_pop = sheet.getCellByPosition(0, 1)    # A2: California population
        c_pop.setFormula('=CENSUS_VALUE(2022;"acs/acs5";"B01003_001E";"state:06")')

        c_label = sheet.getCellByPosition(0, 2)  # A3: variable label
        c_label.setFormula('=CENSUS_VARLABEL(2022;"acs/acs5";"B01003_001E")')

        # --- spillable arrays -----------------------------------------------
        # CENSUS_GET: NAME + total population for every state -> header + 52 rows
        get_rng = sheet.getCellRangeByName("D1:E53")
        get_rng.setArrayFormula(
            '=CENSUS_GET(2022;"acs/acs5";"NAME,B01003_001E";"state:*")')

        # CENSUS_DATASETS: a handful of rows is enough to sanity-check the shape
        ds_rng = sheet.getCellRangeByName("G1:H50")
        ds_rng.setArrayFormula('=CENSUS_DATASETS(2022)')

        doc.calculateAll()

        results["name"] = (c_name.getString(), c_name.getError())
        results["pop"] = (c_pop.getValue(), c_pop.getError())
        results["label"] = (c_label.getString(), c_label.getError())
        results["get"] = (get_rng.getDataArray(),)
        results["datasets"] = (ds_rng.getDataArray(),)
    finally:
        doc.close(False)
        desktop.terminate()

    (name, name_err) = results["name"]
    (pop, pop_err) = results["pop"]
    (label, label_err) = results["label"]
    get_rows = results["get"][0]
    ds_rows = results["datasets"][0]

    print("CENSUS_VALUE(NAME, CA)      :", repr(name), "err=", name_err)
    print("CENSUS_VALUE(pop, CA)       :", pop, "err=", pop_err)
    print("CENSUS_VARLABEL(pop)        :", repr(label), "err=", label_err)
    print("CENSUS_GET header row       :", get_rows[0])
    print("CENSUS_GET first data row   :", get_rows[1])
    print("CENSUS_DATASETS header row  :", ds_rows[0])
    print("CENSUS_DATASETS first row   :", ds_rows[1])

    non_empty_get_rows = [r for r in get_rows if r[0] not in ("", None)]
    non_empty_ds_rows = [r for r in ds_rows if r[0] not in ("", None)]

    checks = {
        "name_no_error": name_err == 0,
        "name_is_california": "California" in name,
        "pop_no_error": pop_err == 0 and pop > 1000000,
        "label_no_error": label_err == 0 and len(label) > 0,
        "get_header_ok": get_rows[0][0] == "NAME",
        "get_has_52_data_rows": len(non_empty_get_rows) - 1 == 52,
        "datasets_header_ok": ds_rows[0][0] == "Dataset",
        "datasets_has_rows": len(non_empty_ds_rows) > 1,
    }
    print("---")
    for name_, ok in checks.items():
        print("CHECK %-24s %s" % (name_, "PASS" if ok else "FAIL"))

    ok = all(checks.values())
    print("RESULT:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
