package com.example.LMrouting.service;

import com.example.LMrouting.dto.IngestionResult;
import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.store.InMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Parses uploaded CSV or XLSX/XLS files into Shipment objects and stores them in-memory.
 *
 * Supported formats:
 *   - .csv / .txt  — comma-separated text
 *   - .xlsx        — Excel 2007+ (Apache POI XSSFWorkbook)
 *   - .xls         — Excel 97-2003 (Apache POI HSSFWorkbook)
 *
 * Validation rules:
 *   - File must be non-empty, ≤ 20 MB, and have a recognised extension
 *   - Required columns: shipping_id, allocation_date, drop_latitude, drop_longitude
 *   - Records with blank shipping_id are skipped
 *   - Records with zero lat/lng are flagged (not excluded) — shown at map origin
 *   - Records beyond hub.max.distance.km are flagged as out-of-range but still allocated
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CsvIngestionService {

    private static final long MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024; // 20 MB (xlsx can be larger)

    private static final List<String> REQUIRED_COLUMNS = List.of(
            "shipping_id", "drop_latitude", "drop_longitude"
    );

    // Column name aliases — maps normalized variants to canonical names
    // This handles Locus exports, common CSV formats, and various naming conventions
    private static final Map<String, String> COLUMN_ALIASES = new LinkedHashMap<>() {{
        // shipping_id aliases
        put("shipment_id", "shipping_id");
        put("shipmentid", "shipping_id");
        put("shippingid", "shipping_id");
        put("awb", "shipping_id");
        put("awb_number", "shipping_id");
        put("awb_no", "shipping_id");
        put("order_id", "shipping_id");
        put("orderid", "shipping_id");
        put("id", "shipping_id");
        put("tracking_id", "shipping_id");
        put("tracking_number", "shipping_id");
        put("consignment_id", "shipping_id");
        put("shipment_no", "shipping_id");
        put("shipment_number", "shipping_id");
        // allocation_date aliases
        put("date", "allocation_date");
        put("delivery_date", "allocation_date");
        put("allocationdate", "allocation_date");
        put("dispatch_date", "allocation_date");
        put("schedule_date", "allocation_date");
        put("scheduled_date", "allocation_date");
        put("planned_date", "allocation_date");
        put("run_date", "allocation_date");
        // drop_latitude aliases
        put("latitude", "drop_latitude");
        put("lat", "drop_latitude");
        put("droplatitude", "drop_latitude");
        put("drop_lat", "drop_latitude");
        put("delivery_latitude", "drop_latitude");
        put("dest_latitude", "drop_latitude");
        put("destination_latitude", "drop_latitude");
        put("customer_latitude", "drop_latitude");
        put("geo_latitude", "drop_latitude");
        put("y", "drop_latitude");
        // drop_longitude aliases
        put("longitude", "drop_longitude");
        put("lng", "drop_longitude");
        put("droplongitude", "drop_longitude");
        put("lon", "drop_longitude");
        put("long", "drop_longitude");
        put("drop_lng", "drop_longitude");
        put("drop_lon", "drop_longitude");
        put("delivery_longitude", "drop_longitude");
        put("dest_longitude", "drop_longitude");
        put("destination_longitude", "drop_longitude");
        put("customer_longitude", "drop_longitude");
        put("geo_longitude", "drop_longitude");
        put("x", "drop_longitude");
        // shipment_flow aliases
        put("flow", "shipment_flow");
        put("type", "shipment_flow");
        put("shipment_type", "shipment_flow");
        put("delivery_type", "shipment_flow");
        put("order_type_flow", "shipment_flow");
        put("forward_reverse", "shipment_flow");
        // drop_pincode aliases
        put("pincode", "drop_pincode");
        put("pin_code", "drop_pincode");
        put("zip", "drop_pincode");
        put("zip_code", "drop_pincode");
        put("postal_code", "drop_pincode");
        put("delivery_pincode", "drop_pincode");
        put("drop_pin", "drop_pincode");
        put("customer_pincode", "drop_pincode");
        // phy_weight aliases
        put("weight", "phy_weight");
        put("physical_weight", "phy_weight");
        put("actual_weight", "phy_weight");
        put("dead_weight", "phy_weight");
        put("wt", "phy_weight");
        // order_type aliases
        put("payment_type", "order_type");
        put("payment_mode", "order_type");
        put("cod_prepaid", "order_type");
        // hub_name aliases
        put("hub", "hub_name");
        put("facility", "hub_name");
        put("facility_name", "hub_name");
        put("warehouse", "hub_name");
        put("origin", "hub_name");
        put("branch", "hub_name");
        // is_heavy aliases
        put("heavy", "is_heavy");
        put("is_heavy_shipment", "is_heavy");
        put("heavy_shipment", "is_heavy");
        // client_id aliases
        put("client", "client_id");
        put("customer_id", "client_id");
        put("merchant_id", "client_id");
        put("seller_id", "client_id");
    }};

    @Value("${hub.latitude:18.4600561}")
    private double hubLat;

    @Value("${hub.longitude:73.8884305}")
    private double hubLng;

    @Value("${hub.max.distance.km:50.0}")
    private double maxDistanceKm;

    private final InMemoryStore store;

    /**
     * Preview what columns are detected in the file — useful for debugging.
     */
    public Map<String, Object> previewColumns(MultipartFile file) throws Exception {
        validateFile(file);
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        List<String[]> rows = filename.endsWith(".xlsx") || filename.endsWith(".xls")
                ? readExcel(file, filename.endsWith(".xlsx")) : readCsv(file);

        if (rows.isEmpty()) return Map.of("error", "File is empty");

        String[] headers = rows.get(0);
        Map<String, Integer> colIndex = buildColumnIndex(headers);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rawHeaders", Arrays.asList(headers));
        result.put("normalizedColumns", new ArrayList<>(colIndex.keySet()));
        result.put("totalDataRows", rows.size() - 1);

        // Show which required columns were found
        Map<String, String> requiredStatus = new LinkedHashMap<>();
        for (String req : REQUIRED_COLUMNS) {
            requiredStatus.put(req, colIndex.containsKey(req) ? "FOUND" : "MISSING");
        }
        result.put("requiredColumns", requiredStatus);

        // Show first data row as sample
        if (rows.size() > 1) {
            Map<String, String> sample = new LinkedHashMap<>();
            String[] firstRow = rows.get(1);
            for (Map.Entry<String, Integer> e : colIndex.entrySet()) {
                if (e.getValue() < firstRow.length) {
                    sample.put(e.getKey(), firstRow[e.getValue()]);
                }
            }
            result.put("sampleRow", sample);
        }
        return result;
    }

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Validate and parse an uploaded CSV or XLSX file.
     * Stores parsed shipments in the in-memory store keyed by allocation_date.
     */
    public IngestionResult ingest(MultipartFile file) {
        validateFile(file);

        String filename = file.getOriginalFilename() != null
                ? file.getOriginalFilename().toLowerCase() : "";

        List<String[]> rows;   // each element is a String[] of cell values
        String[] headers;

        try {
            if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) {
                rows = readExcel(file, filename.endsWith(".xlsx"));
            } else {
                rows = readCsv(file);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to read file: " + e.getMessage());
        }

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("File is empty or has no data rows.");
        }

        // First row is the header
        headers = rows.get(0);
        Map<String, Integer> colIndex = buildColumnIndex(headers);

        // Validate required columns — check both canonical names and aliases
        List<String> missing = new ArrayList<>();
        for (String req : REQUIRED_COLUMNS) {
            if (!colIndex.containsKey(req)) {
                // Also check if any alias for this canonical name is present
                boolean foundViaAlias = COLUMN_ALIASES.entrySet().stream()
                        .anyMatch(e -> e.getValue().equals(req) && colIndex.containsKey(e.getKey()));
                if (!foundViaAlias) missing.add(req);
            }
        }
        if (!missing.isEmpty()) {
            // Build a helpful message showing what columns were found
            List<String> foundCols = new ArrayList<>(colIndex.keySet()).subList(0, Math.min(10, colIndex.size()));
            throw new IllegalArgumentException(
                    "File is missing required columns: " + String.join(", ", missing) +
                    ". Found columns: " + String.join(", ", foundCols) +
                    ". Tip: columns can be named e.g. 'Latitude'/'lat'/'drop_latitude' for coordinates.");
        }

        // Parse data rows
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<Shipment> valid = new ArrayList<>();
        int totalRows = 0;
        int skipped = 0;

        for (int i = 1; i < rows.size(); i++) {
            String[] cols = rows.get(i);
            int rowNum = i + 1; // 1-based for user display

            // Skip completely empty rows
            if (isEmptyRow(cols)) continue;
            totalRows++;

            try {
                ParseResult result = parseRow(cols, headers, colIndex, rowNum);
                if (result.error != null) {
                    errors.add("Row " + rowNum + ": " + result.error);
                    skipped++;
                } else {
                    if (result.warning != null) {
                        warnings.add("Row " + rowNum + " [" + result.shipment.getShippingId() + "]: " + result.warning);
                    }
                    valid.add(result.shipment);
                }
            } catch (Exception e) {
                errors.add("Row " + rowNum + ": parse error — " + e.getMessage());
                skipped++;
            }
        }

        if (valid.isEmpty()) {
            throw new IllegalArgumentException(
                    "No valid shipment records found. " +
                    (errors.isEmpty() ? "Check that required columns have data." : "First error: " + errors.get(0)));
        }

        // Group by allocation_date and store
        Map<String, List<Shipment>> byDate = new LinkedHashMap<>();
        for (Shipment s : valid) {
            byDate.computeIfAbsent(s.getAllocationDate(), k -> new ArrayList<>()).add(s);
        }
        byDate.forEach(store::saveShipments);

        String primaryDate = byDate.entrySet().stream()
                .max(Comparator.comparingInt(e -> e.getValue().size()))
                .map(Map.Entry::getKey).orElse("unknown");

        int outOfRange = (int) valid.stream().filter(Shipment::isOutOfRange).count();
        int zeroCoords = (int) valid.stream()
                .filter(s -> s.getDropLatitude() == 0.0 || s.getDropLongitude() == 0.0).count();

        log.info("Ingestion complete: {} rows, {} valid, {} skipped, {} out-of-range, dates={}",
                totalRows, valid.size(), skipped, outOfRange, byDate.keySet());

        return new IngestionResult(primaryDate, totalRows, valid.size(), skipped,
                outOfRange, zeroCoords, new ArrayList<>(byDate.keySet()), warnings, errors);
    }

    // =========================================================================
    // File readers
    // =========================================================================

    /**
     * Read an XLSX or XLS file into a list of String[] rows.
     * First row is the header. Uses the first non-empty sheet.
     */
    private List<String[]> readExcel(MultipartFile file, boolean isXlsx) throws Exception {
        List<String[]> rows = new ArrayList<>();

        try (InputStream is = file.getInputStream();
             Workbook workbook = isXlsx ? new XSSFWorkbook(is) : new HSSFWorkbook(is)) {

            // Find first non-empty sheet
            Sheet sheet = null;
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet s = workbook.getSheetAt(i);
                if (s.getPhysicalNumberOfRows() > 0) {
                    sheet = s;
                    break;
                }
            }
            if (sheet == null) {
                throw new IllegalArgumentException("Excel file has no data sheets.");
            }

            log.info("Reading Excel sheet: '{}' ({} rows)", sheet.getSheetName(), sheet.getPhysicalNumberOfRows());

            // Find the header row — scan first 10 rows for one containing known column names
            int headerRowIdx = findHeaderRow(sheet);
            if (headerRowIdx < 0) {
                // Fall back to row 0
                headerRowIdx = sheet.getFirstRowNum();
            }

            int maxCols = 0;
            // First pass: find max columns
            for (Row row : sheet) {
                if (row.getLastCellNum() > maxCols) maxCols = row.getLastCellNum();
            }

            // Second pass: read all rows from header onwards
            for (int r = headerRowIdx; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                String[] cells = new String[maxCols];
                Arrays.fill(cells, "");
                if (row != null) {
                    for (int c = 0; c < maxCols; c++) {
                        Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        cells[c] = cellToString(cell);
                    }
                }
                rows.add(cells);
            }
        }
        return rows;
    }

    /**
     * Scan the first 10 rows to find the header row.
     * A header row is one that contains at least 2 of the required column names.
     */
    private int findHeaderRow(Sheet sheet) {
        int limit = Math.min(10, sheet.getLastRowNum() + 1);
        for (int r = sheet.getFirstRowNum(); r < limit; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            int matches = 0;
            for (Cell cell : row) {
                String raw = cellToString(cell).toLowerCase().trim();
                String val = raw.replaceAll("[^a-z0-9_]", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
                // Check canonical names, aliases, and common keywords
                if (REQUIRED_COLUMNS.contains(val) || COLUMN_ALIASES.containsKey(val)
                        || raw.contains("lat") || raw.contains("lon") || raw.contains("lng")
                        || raw.contains("shipment") || raw.contains("awb") || raw.contains("date")
                        || raw.contains("pincode") || raw.contains("zip") || raw.contains("weight")) {
                    matches++;
                }
            }
            if (matches >= 2) return r;
        }
        return -1;
    }

    /**
     * Convert an Excel cell to a clean String value.
     * Handles numeric, string, boolean, formula, and date cells.
     */
    private String cellToString(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    // Format date as string
                    java.util.Date d = cell.getDateCellValue();
                    return new java.text.SimpleDateFormat("yyyy-MM-dd").format(d);
                }
                double val = cell.getNumericCellValue();
                // Avoid scientific notation for large IDs and avoid trailing .0 for integers
                if (val == Math.floor(val) && !Double.isInfinite(val) && Math.abs(val) < 1e15) {
                    return String.valueOf((long) val);
                }
                return String.valueOf(val);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    // Try to evaluate the formula
                    FormulaEvaluator evaluator = cell.getSheet().getWorkbook()
                            .getCreationHelper().createFormulaEvaluator();
                    CellValue cv = evaluator.evaluate(cell);
                    if (cv.getCellType() == CellType.NUMERIC) {
                        double fval = cv.getNumberValue();
                        if (fval == Math.floor(fval) && !Double.isInfinite(fval)) {
                            return String.valueOf((long) fval);
                        }
                        return String.valueOf(fval);
                    }
                    return cv.getStringValue() != null ? cv.getStringValue().trim() : "";
                } catch (Exception e) {
                    return cell.getCellFormula();
                }
            case BLANK:
            case _NONE:
            default:
                return "";
        }
    }

    /**
     * Read a CSV/TXT file into a list of String[] rows.
     */
    private List<String[]> readCsv(MultipartFile file) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) {
                    // Strip BOM
                    if (line.startsWith("\uFEFF")) line = line.substring(1);
                    first = false;
                }
                if (!line.isBlank()) {
                    rows.add(splitCsvLine(line));
                }
            }
        }
        return rows;
    }

    // =========================================================================
    // Row parsing
    // =========================================================================

    private ParseResult parseRow(String[] cols, String[] headers,
                                  Map<String, Integer> colIndex, int rowNum) {
        // Pad short rows
        if (cols.length < headers.length) {
            cols = Arrays.copyOf(cols, headers.length);
            for (int i = 0; i < cols.length; i++) if (cols[i] == null) cols[i] = "";
        }

        String shippingId = get(cols, colIndex, "shipping_id");
        if (shippingId == null || shippingId.isBlank()) {
            return ParseResult.error("shipping_id is blank — row skipped");
        }

        String allocationDate = get(cols, colIndex, "allocation_date");
        if (allocationDate == null || allocationDate.isBlank()) {
            // Default to today's date in dd-MMM-yy format if not provided
            allocationDate = java.time.LocalDate.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yy", java.util.Locale.ENGLISH));
        }

        double lat = parseDouble(get(cols, colIndex, "drop_latitude"), 0.0);
        double lng = parseDouble(get(cols, colIndex, "drop_longitude"), 0.0);

        String warning = null;
        if (lat == 0.0 || lng == 0.0) {
            warning = "zero lat/lng — will appear at map origin";
        }

        boolean outOfRange = false;
        double distKm = 0.0;
        if (lat != 0.0 && lng != 0.0) {
            distKm = GoogleMapsService.haversine(hubLat, hubLng, lat, lng);
            if (distKm > maxDistanceKm) {
                outOfRange = true;
                String w = String.format("%.1f km from hub (max %.0f km) — marked out-of-range", distKm, maxDistanceKm);
                warning = warning == null ? w : warning + "; " + w;
            }
        }

        Shipment s = Shipment.builder()
                .shippingId(shippingId.trim())
                .allocationDate(allocationDate.trim())
                .hubName(getOrDefault(cols, colIndex, "hub_name", ""))
                .dropPincode(getOrDefault(cols, colIndex, "drop_pincode", ""))
                .cityName(getOrDefault(cols, colIndex, "city_name",
                          getOrDefault(cols, colIndex, "city", "")))
                .stateName(getOrDefault(cols, colIndex, "state_name",
                           getOrDefault(cols, colIndex, "state", "")))
                .shipmentFlow(getOrDefault(cols, colIndex, "shipment_flow", "Forward"))
                .isHeavy(parseInt(getOrDefault(cols, colIndex, "is_heavy", "0"), 0))
                .phyWeight(parseDouble(getOrDefault(cols, colIndex, "phy_weight", "0"), 0.0))
                .volWeight(parseDouble(getOrDefault(cols, colIndex, "vol_weight", "0"), 0.0))
                .orderType(getOrDefault(cols, colIndex, "order_type", ""))
                .dropLatitude(lat)
                .dropLongitude(lng)
                .clientId(getOrDefault(cols, colIndex, "client_id", ""))
                .srName(getOrDefault(cols, colIndex, "sr_name", ""))
                .runNumber(parseInt(getOrDefault(cols, colIndex, "run_number", "0"), 0))
                .rate(parseDouble(getOrDefault(cols, colIndex, "rate", "0"), 0.0))
                .expectedPayout(parseDouble(getOrDefault(cols, colIndex, "expected_payout", "0"), 0.0))
                .outOfRange(outOfRange)
                .distanceFromHubKm(distKm)
                .build();

        return warning != null ? ParseResult.withWarning(s, warning) : ParseResult.ok(s);
    }

    // =========================================================================
    // File validation
    // =========================================================================

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file uploaded or file is empty.");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "File too large: " + (file.getSize() / 1024 / 1024) + " MB. Maximum allowed: 20 MB.");
        }
        String name = file.getOriginalFilename();
        if (name != null && !name.isBlank()) {
            String lower = name.toLowerCase();
            if (!lower.endsWith(".csv") && !lower.endsWith(".txt")
                    && !lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
                throw new IllegalArgumentException(
                        "Unsupported file type: '" + name + "'. Accepted: .csv, .xlsx, .xls");
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Map<String, Integer> buildColumnIndex(String[] headers) {
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < headers.length; i++) {
            if (headers[i] == null) continue;
            String name = headers[i].trim().toLowerCase()
                    .replaceAll("[^a-z0-9_]", "_")
                    .replaceAll("_+", "_")
                    .replaceAll("^_|_$", "");
            if (name.isBlank()) continue;
            // Store the normalized name
            index.put(name, i);
            // Also store the canonical alias if this name maps to one
            String canonical = COLUMN_ALIASES.get(name);
            if (canonical != null && !index.containsKey(canonical)) {
                index.put(canonical, i);
            }
        }
        return index;
    }

    private boolean isEmptyRow(String[] cols) {
        for (String c : cols) if (c != null && !c.isBlank()) return false;
        return true;
    }

    private String[] splitCsvLine(String line) {
        List<String> result = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"'); i++;
                } else { inQuotes = !inQuotes; }
            } else if (c == ',' && !inQuotes) {
                result.add(current.toString().trim()); current.setLength(0);
            } else { current.append(c); }
        }
        result.add(current.toString().trim());
        return result.toArray(new String[0]);
    }

    private String get(String[] cols, Map<String, Integer> index, String key) {
        Integer i = index.get(key);
        if (i == null || i >= cols.length) return null;
        String val = cols[i];
        return (val == null || val.isBlank()) ? null : val.trim();
    }

    private String getOrDefault(String[] cols, Map<String, Integer> index, String key, String def) {
        String val = get(cols, index, key);
        return val != null ? val : def;
    }

    private double parseDouble(String s, double def) {
        if (s == null || s.isBlank()) return def;
        try { return Double.parseDouble(s.trim().replace(",", "")); }
        catch (NumberFormatException e) { return def; }
    }

    private int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return (int) Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private record ParseResult(Shipment shipment, String warning, String error) {
        static ParseResult ok(Shipment s) { return new ParseResult(s, null, null); }
        static ParseResult withWarning(Shipment s, String w) { return new ParseResult(s, w, null); }
        static ParseResult error(String e) { return new ParseResult(null, null, e); }
    }
}
