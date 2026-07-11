package com.example.census;

import java.util.List;

import com.sun.star.lang.IllegalArgumentException;
import com.sun.star.lang.Locale;
import com.sun.star.lang.XSingleComponentFactory;
import com.sun.star.lib.uno.helper.Factory;
import com.sun.star.lib.uno.helper.WeakBase;
import com.sun.star.registry.XRegistryKey;
import com.sun.star.uno.Any;
import com.sun.star.uno.Type;
import com.sun.star.uno.TypeClass;

/**
 * LibreOffice Calc add-in exposing US Census Bureau worksheet functions.
 *
 * <p>Implements the custom {@link XCensus} interface plus the standard add-in
 * plumbing ({@code com.sun.star.sheet.XAddIn}, {@code XServiceName},
 * {@code XServiceInfo}). Function display names, descriptions and per-argument
 * help live in config/CalcAddIns.xcu; the {@code XAddIn} accessors below return
 * the programmatic names as a safe fallback.
 *
 * <p>Errors are surfaced as thrown {@link IllegalArgumentException}s, which Calc
 * renders as error values (e.g. #VALUE!) in the cell rather than as exception
 * strings.
 */
public final class CensusImpl extends WeakBase
        implements XCensus,
                   com.sun.star.sheet.XAddIn,
                   com.sun.star.lang.XServiceName,
                   com.sun.star.lang.XServiceInfo {

    /** Implementation name: must match the AddInInfo node in CalcAddIns.xcu. */
    private static final String IMPLEMENTATION_NAME = "com.example.census.CensusImpl";

    /** The one service that marks this component as a Calc add-in. */
    private static final String ADDIN_SERVICE = "com.sun.star.sheet.AddIn";

    private static final String[] SERVICE_NAMES = { ADDIN_SERVICE, IMPLEMENTATION_NAME };

    /** Current locale (tracked for XLocalizable; metadata is English-only here). */
    private Locale locale = new Locale("en", "US", "");

    // ------------------------------------------------------------------ //
    // XCensus - the actual worksheet functions                           //
    // ------------------------------------------------------------------ //

    /** {@inheritDoc} */
    public Object[][] censusGet(Object year, String dataset, String variables, String geography,
                                Object inGeography, Object apiKey) throws IllegalArgumentException {
        String y = requireYear(year);
        String ds = requireNonEmpty(dataset, "dataset");
        String vars = requireNonEmpty(variables, "variables");
        String forClause = requireNonEmpty(geography, "geography");
        try {
            Object[][] table = CensusClient.table(
                    y, ds, vars, forClause, optString(inGeography), optString(apiKey));
            if (table.length == 0) {
                throw new java.lang.IllegalArgumentException(
                        "No data returned for " + ds + "/" + y);
            }
            return table;
        } catch (RuntimeException e) {
            throw asCalcError(e);
        }
    }

    /** {@inheritDoc} */
    public Object censusValue(Object year, String dataset, String variable, String geography,
                              Object inGeography, Object apiKey) throws IllegalArgumentException {
        String y = requireYear(year);
        String ds = requireNonEmpty(dataset, "dataset");
        String v = requireNonEmpty(variable, "variable");
        String forClause = requireNonEmpty(geography, "geography");
        try {
            Object[][] table = CensusClient.table(
                    y, ds, v, forClause, optString(inGeography), optString(apiKey));
            if (table.length < 2) {
                throw new java.lang.IllegalArgumentException(
                        "No data row returned for " + v + " (" + ds + "/" + y + ")");
            }
            int col = indexOf(table[0], v);
            if (col < 0) {
                throw new java.lang.IllegalArgumentException(
                        "Variable " + v + " not present in the response header");
            }
            return valueCell(table[1][col]);
        } catch (RuntimeException e) {
            throw asCalcError(e);
        }
    }

    /** {@inheritDoc} */
    public String censusVarLabel(Object year, String dataset, String variable)
            throws IllegalArgumentException {
        String y = requireYear(year);
        String ds = requireNonEmpty(dataset, "dataset");
        String v = requireNonEmpty(variable, "variable");
        try {
            return CensusClient.variableLabel(y, ds, v);
        } catch (RuntimeException e) {
            throw asCalcError(e);
        }
    }

    /** {@inheritDoc} */
    public Object[][] censusDatasets(Object year) throws IllegalArgumentException {
        String y = requireYear(year);
        try {
            List<Object[]> rows = CensusClient.datasetList(y);
            if (rows.isEmpty()) {
                throw new java.lang.IllegalArgumentException("No datasets found for " + y);
            }
            Object[][] out = new Object[rows.size() + 1][2];
            out[0][0] = "Dataset";
            out[0][1] = "Title";
            for (int r = 0; r < rows.size(); r++) {
                out[r + 1][0] = rows.get(r)[0];
                out[r + 1][1] = rows.get(r)[1];
            }
            return out;
        } catch (RuntimeException e) {
            throw asCalcError(e);
        }
    }

    // ------------------------------------------------------------------ //
    // Argument / value helpers                                           //
    // ------------------------------------------------------------------ //

    /** Unwrap a 1x1 matrix (a single-cell reference may arrive as Object[][]). */
    private static Object scalar(Object arg) {
        if (arg instanceof Object[][]) {
            Object[][] m = (Object[][]) arg;
            return (m.length > 0 && m[0].length > 0) ? m[0][0] : null;
        }
        return arg;
    }

    /**
     * Coerce the required "year" argument (number or string cell) to a plain
     * digit-string path segment, e.g. 2022.0 -&gt; "2022".
     */
    private static String requireYear(Object arg) throws IllegalArgumentException {
        Object v = scalar(arg);
        if (v == null || v instanceof Any) {
            throw new IllegalArgumentException("year is required");
        }
        String y;
        if (v instanceof Number) {
            long whole = (long) Math.floor(((Number) v).doubleValue());
            y = String.valueOf(whole);
        } else {
            y = String.valueOf(v).trim();
        }
        if (y.isEmpty()) {
            throw new IllegalArgumentException("year is required");
        }
        return y;
    }

    private static String requireNonEmpty(String value, String name) throws IllegalArgumentException {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    /** Interpret an optional string argument; VOID/empty -> null. */
    private static String optString(Object arg) {
        Object v = scalar(arg);
        if (v == null || v instanceof Any) {
            return null; // omitted argument arrives as VOID Any
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static int indexOf(Object[] headerRow, String variable) {
        for (int i = 0; i < headerRow.length; i++) {
            if (variable.equalsIgnoreCase(String.valueOf(headerRow[i]))) {
                return i;
            }
        }
        return -1;
    }

    /** Map a raw cell to a number when it parses as one, else leave as text/empty. */
    private static Object valueCell(Object raw) {
        if (raw == null) {
            return new Any(new Type(TypeClass.VOID), null);
        }
        String s = String.valueOf(raw);
        if (s.isEmpty()) {
            return new Any(new Type(TypeClass.VOID), null);
        }
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException nfe) {
            return s;
        }
    }

    /** Normalize any thrown error into a Calc-facing IllegalArgumentException. */
    private static IllegalArgumentException asCalcError(RuntimeException e) {
        if (e instanceof IllegalArgumentException) {
            return (IllegalArgumentException) e;
        }
        return new IllegalArgumentException(e.getMessage());
    }

    // ------------------------------------------------------------------ //
    // XAddIn - function metadata                                         //
    //                                                                    //
    // Calc uses getDisplayFunctionName() as the AUTHORITATIVE display    //
    // (formula) name; CalcAddIns.xcu only supplies wizard help. So these //
    // must map programmatic <-> display names explicitly, or the cell    //
    // formula (=CENSUS_GET(...)) resolves to #NAME?.                     //
    // ------------------------------------------------------------------ //

    /** { programmatic, display } for every exposed function. */
    private static final String[][] FUNCS = {
        { "censusGet",      "CENSUS_GET" },
        { "censusValue",    "CENSUS_VALUE" },
        { "censusVarLabel", "CENSUS_VARLABEL" },
        { "censusDatasets", "CENSUS_DATASETS" },
    };

    /** Per-function one-line descriptions (function wizard). */
    private static String funcDescription(String prog) {
        if ("censusGet".equals(prog)) {
            return "Runs a Census API data query and returns the full result table "
                    + "(header row plus one row per geography).";
        }
        if ("censusValue".equals(prog)) {
            return "Returns a single scalar value for one variable and one specific geography.";
        }
        if ("censusVarLabel".equals(prog)) {
            return "Returns the human-readable label for a variable code.";
        }
        if ("censusDatasets".equals(prog)) {
            return "Lists the datasets available for a year as a (Dataset, Title) array.";
        }
        return "";
    }

    private static final String ARG_KEY = "api_key";
    private static final String ARG_KEY_DESC =
        "Census API key; if omitted, the CENSUS_API_KEY environment variable is used. "
        + "A key from one or the other is required -- the API rejects keyless data requests.";
    private static final String ARG_IN = "in_geography";
    private static final String ARG_IN_DESC =
        "Optional. The \"in\" geography clause, e.g. \"state:06\", needed when geography is "
        + "a sub-state level such as county or tract.";

    /** Per-function argument display names, indexed by position. */
    private static String[] argNames(String prog) {
        if ("censusGet".equals(prog)) {
            return new String[] { "year", "dataset", "variables", "geography", ARG_IN, ARG_KEY };
        }
        if ("censusValue".equals(prog)) {
            return new String[] { "year", "dataset", "variable", "geography", ARG_IN, ARG_KEY };
        }
        if ("censusVarLabel".equals(prog)) {
            return new String[] { "year", "dataset", "variable" };
        }
        if ("censusDatasets".equals(prog)) {
            return new String[] { "year" };
        }
        return new String[0];
    }

    /** Per-function argument descriptions, indexed by position. */
    private static String[] argDescriptions(String prog) {
        if ("censusGet".equals(prog)) {
            return new String[] {
                "The data year, e.g. 2022.",
                "The dataset path, e.g. \"acs/acs5\" (ACS 5-year estimates) or \"dec/pl\".",
                "Comma-separated variable codes to fetch, e.g. \"NAME,B01003_001E\".",
                "The \"for\" geography clause, e.g. \"state:*\" or \"county:*\".",
                ARG_IN_DESC,
                ARG_KEY_DESC,
            };
        }
        if ("censusValue".equals(prog)) {
            return new String[] {
                "The data year, e.g. 2022.",
                "The dataset path, e.g. \"acs/acs5\".",
                "A single variable code, e.g. \"B01003_001E\".",
                "The \"for\" geography clause identifying one specific geography (not a "
                    + "wildcard), e.g. \"state:06\".",
                ARG_IN_DESC,
                ARG_KEY_DESC,
            };
        }
        if ("censusVarLabel".equals(prog)) {
            return new String[] {
                "The data year, e.g. 2022.",
                "The dataset path, e.g. \"acs/acs5\".",
                "The variable code to look up, e.g. \"B01003_001E\".",
            };
        }
        if ("censusDatasets".equals(prog)) {
            return new String[] { "The data year, e.g. 2022." };
        }
        return new String[0];
    }

    public String getProgrammaticFuntionName(String displayName) {
        for (String[] f : FUNCS) {
            if (f[1].equals(displayName)) return f[0];
        }
        return "";
    }

    public String getDisplayFunctionName(String programmaticName) {
        for (String[] f : FUNCS) {
            if (f[0].equals(programmaticName)) return f[1];
        }
        return "";
    }

    public String getFunctionDescription(String programmaticName) {
        return funcDescription(programmaticName);
    }

    public String getDisplayArgumentName(String programmaticName, int argument) {
        String[] a = argNames(programmaticName);
        return (argument >= 0 && argument < a.length) ? a[argument] : "";
    }

    public String getArgumentDescription(String programmaticName, int argument) {
        String[] a = argDescriptions(programmaticName);
        return (argument >= 0 && argument < a.length) ? a[argument] : "";
    }

    public String getProgrammaticCategoryName(String programmaticName) {
        return "Add-In";
    }

    public String getDisplayCategoryName(String programmaticName) {
        return "Add-In";
    }

    // ------------------------------------------------------------------ //
    // XLocalizable (inherited via XAddIn)                                //
    // ------------------------------------------------------------------ //

    public void setLocale(Locale locale) {
        this.locale = locale;
    }

    public Locale getLocale() {
        return locale;
    }

    // ------------------------------------------------------------------ //
    // XServiceName / XServiceInfo                                        //
    // ------------------------------------------------------------------ //

    public String getServiceName() {
        return IMPLEMENTATION_NAME;
    }

    public String getImplementationName() {
        return IMPLEMENTATION_NAME;
    }

    public boolean supportsService(String service) {
        for (String s : SERVICE_NAMES) {
            if (s.equals(service)) return true;
        }
        return false;
    }

    public String[] getSupportedServiceNames() {
        return SERVICE_NAMES.clone();
    }

    // ------------------------------------------------------------------ //
    // UNO component registration entry points                           //
    // ------------------------------------------------------------------ //

    public static XSingleComponentFactory __getComponentFactory(String implName) {
        if (IMPLEMENTATION_NAME.equals(implName)) {
            return Factory.createComponentFactory(CensusImpl.class, SERVICE_NAMES);
        }
        return null;
    }

    public static boolean __writeRegistryServiceInfo(XRegistryKey regKey) {
        return Factory.writeRegistryServiceInfo(IMPLEMENTATION_NAME, SERVICE_NAMES, regKey);
    }
}
