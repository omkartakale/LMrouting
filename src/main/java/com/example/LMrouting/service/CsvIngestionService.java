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
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Fast CSV/XLSX ingestion service.
 *
 * Performance optimisations applied:
 *  - Single-pass Excel reading (no double iteration)
 *  - FormulaEvaluator created once per workbook, not per cell
 *  - SimpleDateFormat created once per parse call
 *  - Streaming CSV reading (no full in-memory load before parse)
 *  - Haversine computed only when lat/lng are non-zero
 *  - Column alias lookup is O(1) via pre-built reverse map
 *  - Warnings capped at 50 to avoid huge response payloads
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CsvIngestionService {

    private static final long MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024;
    private static final int  MAX_WARNINGS        = 50;

    // Only truly required — allocation_date is optional (defaults to today)
    private static final List<String> REQUIRED_COLUMNS = List.of(
            "shipping_id", "drop_latitude", "drop_longitude"
    );

    // ── Column aliases ────────────────────────────────────────────────────────
    // Maps every known variant → canonical column name used in parseRow()
    private static final Map<String, String> COLUMN_ALIASES;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        // shipping_id
        for (String s : new String[]{"shipment_id","shipmentid","shippingid","awb","awb_number",
                "awb_no","order_id","orderid","tracking_id","tracking_number",
                "consignment_id","shipment_no","shipment_number","id"})
            m.put(s, "shipping_id");
        // allocation_date
        for (String s : new String[]{"date","delivery_date","allocationdate","dispatch_date",
                "schedule_date","scheduled_date","planned_date","run_date"})
            m.put(s, "allocation_date");
        // drop_latitude
        for (String s : new String[]{"latitude","lat","droplatitude","drop_lat",
                "delivery_latitude","dest_latitude","destination_latitude",
                "customer_latitude","geo_latitude","y"})
            m.put(s, "drop_latitude");
        // drop_longitude
        for (String s : new String[]{"longitude","lng","lon","long","droplongitude","drop_lng",
                "drop_lon","delivery_longitude","dest_longitude","destination_longitude",
                "customer_longitude","geo_longitude","x"})
            m.put(s, "drop_longitude");
        // drop_pincode
        for (String s : new String[]{"pincode","pin_code","zip","zip_code","postal_code",
                "delivery_pincode","drop_pin","customer_pincode","droppincode","drop_pincode_value"})
            m.put(s, "drop_pincode");
        // shipment_flow
        for (String s : new String[]{"flow","type","shipment_type","delivery_type",
                "order_type_flow","forward_reverse"})
            m.put(s, "shipment_flow");
        // phy_weight
        for (String s : new String[]{"weight","physical_weight","actual_weight","dead_weight","wt"})
            m.put(s, "phy_weight");
        // order_type
        for (String s : new String[]{"payment_type","payment_mode","cod_prepaid"})
            m.put(s, "order_type");
        // hub_name
        for (String s : new String[]{"hub","facility","facility_name","warehouse","origin","branch"})
            m.put(s, "hub_name");
        // is_heavy
        for (String s : new String[]{"heavy","is_heavy_shipment","heavy_shipment"})
            m.put(s, "is_heavy");
        // client_id
        for (String s : new String[]{"client","customer_id","merchant_id","seller_id"})
            m.put(s, "client_id");
        // expected_payout — the primary earnings field used for payout-sorted capacity selection
        // "Expected Payout" normalises to "expected_payout" automatically via normalizeColName,
        // but we also add common short-form aliases so CSVs with different headers are handled.
        for (String s : new String[]{"payout","exp_payout","expectedpayout","expected_pay",
                "expected_earning","expected_earnings","earning","earnings","payout_amount",
                "shipment_payout","delivery_payout"})
            m.put(s, "expected_payout");
        // shipment_flow — "shipmenttype" is the column name used in the Locus CSV export
        // (values: "Delivery" → treated as Forward, "Reverse" → treated as Reverse)
        for (String s : new String[]{"shipmenttype","shipment_type_locus","deliverytype"})
            m.put(s, "shipment_flow");
        // sr_name
        for (String s : new String[]{"srname","sr_id","srid","delivery_user_id","deliveryuserid",
                "rider","rider_name","rider_id","delivery_boy","delivery_agent"})
            m.put(s, "sr_name");
        // priority — P0, P1, P2 shipment priority tier
        for (String s : new String[]{"priority","shipment_priority","delivery_priority",
                "sla","sla_type","tier","service_level"})
            m.put(s, "priority");
        COLUMN_ALIASES = Collections.unmodifiableMap(m);
    }

    @Value("${hub.latitude:18.4600561}")  private double hubLat;
    @Value("${hub.longitude:73.8884305}") private double hubLng;
    @Value("${hub.max.distance.km:50.0}") private double maxDistanceKm;

    private final InMemoryStore store;
    private final PincodeBoundaryService pincodeBoundaryService;

    // =========================================================================
    // Public API
    // =========================================================================

    public IngestionResult ingest(MultipartFile file) {
        long t0 = System.currentTimeMillis();
        validateFile(file);

        String filename = Optional.ofNullable(file.getOriginalFilename())
                .map(String::toLowerCase).orElse("");
        boolean isExcel = filename.endsWith(".xlsx") || filename.endsWith(".xls");

        List<String> warnings = new ArrayList<>();
        List<String> errors   = new ArrayList<>();
        List<Shipment> valid  = new ArrayList<>();
        int[] counters = {0, 0}; // [totalRows, skipped]

        try {
            if (isExcel) {
                parseExcel(file, filename.endsWith(".xlsx"), valid, warnings, errors, counters);
            } else {
                parseCsv(file, valid, warnings, errors, counters);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to read file: " + e.getMessage(), e);
        }

        int totalRows = counters[0];
        int skipped   = counters[1];

        if (valid.isEmpty()) {
            throw new IllegalArgumentException(
                    "No valid shipment records found. " +
                    (errors.isEmpty() ? "Check that required columns have data."
                                      : "First error: " + errors.get(0)));
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

        long elapsed = System.currentTimeMillis() - t0;
        log.info("Ingestion done in {}ms: {} rows, {} valid, {} skipped, {} out-of-range, dates={}",
                elapsed, totalRows, valid.size(), skipped, outOfRange, byDate.keySet());

        return new IngestionResult(primaryDate, totalRows, valid.size(), skipped,
                outOfRange, zeroCoords, new ArrayList<>(byDate.keySet()), warnings, errors);
    }

    /** Preview detected columns without full parsing — for debugging. */
    public Map<String, Object> previewColumns(MultipartFile file) throws Exception {
        validateFile(file);
        String filename = Optional.ofNullable(file.getOriginalFilename())
                .map(String::toLowerCase).orElse("");

        // Read only the first two rows
        String[] headers = null;
        String[] sampleRow = null;

        if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) {
            try (InputStream is = file.getInputStream();
                 Workbook wb = filename.endsWith(".xlsx") ? new XSSFWorkbook(is) : new HSSFWorkbook(is)) {
                Sheet sheet = findFirstNonEmptySheet(wb);
                if (sheet != null) {
                    int hIdx = findHeaderRowIndex(sheet);
                    if (hIdx < 0) hIdx = sheet.getFirstRowNum();
                    headers = readExcelRow(sheet.getRow(hIdx), null);
                    Row next = sheet.getRow(hIdx + 1);
                    if (next != null) sampleRow = readExcelRow(next, null);
                }
            }
        } else {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
                String line = br.readLine();
                if (line != null) {
                    if (line.startsWith("\uFEFF")) line = line.substring(1);
                    headers = splitCsvLine(line);
                }
                String line2 = br.readLine();
                if (line2 != null) sampleRow = splitCsvLine(line2);
            }
        }

        if (headers == null) return Map.of("error", "File is empty");

        Map<String, Integer> colIndex = buildColumnIndex(headers);
        Map<String, String> reqStatus = new LinkedHashMap<>();
        for (String req : REQUIRED_COLUMNS)
            reqStatus.put(req, colIndex.containsKey(req) ? "FOUND" : "MISSING");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rawHeaders", Arrays.asList(headers));
        result.put("normalizedColumns", new ArrayList<>(colIndex.keySet()));
        result.put("requiredColumns", reqStatus);
        if (sampleRow != null) {
            Map<String, String> sample = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : colIndex.entrySet()) {
                if (e.getValue() < sampleRow.length) sample.put(e.getKey(), sampleRow[e.getValue()]);
            }
            result.put("sampleRow", sample);
        }
        return result;
    }

    // =========================================================================
    // Excel parsing — single pass, one FormulaEvaluator per workbook
    // =========================================================================

    private void parseExcel(MultipartFile file, boolean isXlsx,
                             List<Shipment> valid, List<String> warnings,
                             List<String> errors, int[] counters) throws Exception {

        try (InputStream is = file.getInputStream();
             Workbook wb = isXlsx ? new XSSFWorkbook(is) : new HSSFWorkbook(is)) {

            // Create evaluator ONCE for the whole workbook
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            // Create date formatter ONCE
            SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd");

            Sheet sheet = findFirstNonEmptySheet(wb);
            if (sheet == null) throw new IllegalArgumentException("Excel file has no data sheets.");

            log.info("Parsing Excel sheet '{}' ({} physical rows)", sheet.getSheetName(), sheet.getPhysicalNumberOfRows());

            int headerRowIdx = findHeaderRowIndex(sheet);
            if (headerRowIdx < 0) headerRowIdx = sheet.getFirstRowNum();

            // Read header row
            Row headerRow = sheet.getRow(headerRowIdx);
            if (headerRow == null) throw new IllegalArgumentException("Header row is empty.");
            String[] headers = readExcelRow(headerRow, evaluator);

            Map<String, Integer> colIndex = buildColumnIndex(headers);
            validateRequiredColumns(colIndex);

            // Single pass over data rows
            int lastRow = sheet.getLastRowNum();
            for (int r = headerRowIdx + 1; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                // Fast empty-row check: skip rows where all cells are blank
                if (row.getPhysicalNumberOfCells() == 0) continue;

                String[] cols = readExcelRow(row, evaluator);
                if (isEmptyRow(cols)) continue;

                counters[0]++; // totalRows
                int rowNum = r + 1;

                try {
                    ParseResult pr = parseRow(cols, headers, colIndex, rowNum);
                    if (pr.error != null) {
                        if (errors.size() < 100) errors.add("Row " + rowNum + ": " + pr.error);
                        counters[1]++;
                    } else {
                        if (pr.warning != null && warnings.size() < MAX_WARNINGS)
                            warnings.add("Row " + rowNum + " [" + pr.shipment.getShippingId() + "]: " + pr.warning);
                        valid.add(pr.shipment);
                    }
                } catch (Exception e) {
                    if (errors.size() < 100) errors.add("Row " + rowNum + ": " + e.getMessage());
                    counters[1]++;
                }
            }
        }
    }

    /** Read a single Excel row into a String array using the shared evaluator. */
    private String[] readExcelRow(Row row, FormulaEvaluator evaluator) {
        if (row == null) return new String[0];
        int lastCol = row.getLastCellNum();
        if (lastCol <= 0) return new String[0];
        String[] cells = new String[lastCol];
        for (int c = 0; c < lastCol; c++) {
            Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            cells[c] = cellToString(cell, evaluator);
        }
        return cells;
    }

    private Sheet findFirstNonEmptySheet(Workbook wb) {
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            Sheet s = wb.getSheetAt(i);
            if (s.getPhysicalNumberOfRows() > 0) return s;
        }
        return null;
    }

    private int findHeaderRowIndex(Sheet sheet) {
        int limit = Math.min(10, sheet.getLastRowNum() + 1);
        for (int r = sheet.getFirstRowNum(); r < limit; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            int matches = 0;
            for (Cell cell : row) {
                String raw = cellToString(cell, null).toLowerCase().trim();
                String val = normalizeColName(raw);
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
     * Convert a cell to String. evaluator may be null (for header scanning).
     * Uses the shared evaluator — NOT created per cell.
     */
    private String cellToString(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) return "";
        CellType type = cell.getCellType();

        // Resolve formula to its cached value type
        if (type == CellType.FORMULA) {
            if (evaluator != null) {
                try {
                    CellValue cv = evaluator.evaluate(cell);
                    type = cv.getCellType();
                    switch (type) {
                        case NUMERIC: return formatNumeric(cv.getNumberValue());
                        case STRING:  return cv.getStringValue() != null ? cv.getStringValue().trim() : "";
                        case BOOLEAN: return String.valueOf(cv.getBooleanValue());
                        default:      return "";
                    }
                } catch (Exception e) {
                    // Fall through to cached value
                }
            }
            // No evaluator — use cached value type
            type = cell.getCachedFormulaResultType();
        }

        switch (type) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return new SimpleDateFormat("yyyy-MM-dd").format(cell.getDateCellValue());
                }
                return formatNumeric(cell.getNumericCellValue());
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            default:
                return "";
        }
    }

    private static String formatNumeric(double val) {
        if (val == Math.floor(val) && !Double.isInfinite(val) && Math.abs(val) < 1e15) {
            return String.valueOf((long) val);
        }
        return String.valueOf(val);
    }

    // =========================================================================
    // CSV parsing — streaming, no full in-memory load
    // =========================================================================

    private void parseCsv(MultipartFile file, List<Shipment> valid,
                           List<String> warnings, List<String> errors, int[] counters) throws IOException {

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8), 65536)) {

            // Read header
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank())
                throw new IllegalArgumentException("CSV file is empty or has no header row.");
            if (headerLine.startsWith("\uFEFF")) headerLine = headerLine.substring(1);

            String[] headers = splitCsvLine(headerLine);
            Map<String, Integer> colIndex = buildColumnIndex(headers);
            validateRequiredColumns(colIndex);

            // Stream data rows
            String line;
            int lineNum = 1;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (line.isBlank()) continue;

                String[] cols = splitCsvLine(line);
                if (isEmptyRow(cols)) continue;
                counters[0]++;

                try {
                    ParseResult pr = parseRow(cols, headers, colIndex, lineNum);
                    if (pr.error != null) {
                        if (errors.size() < 100) errors.add("Row " + lineNum + ": " + pr.error);
                        counters[1]++;
                    } else {
                        if (pr.warning != null && warnings.size() < MAX_WARNINGS)
                            warnings.add("Row " + lineNum + " [" + pr.shipment.getShippingId() + "]: " + pr.warning);
                        valid.add(pr.shipment);
                    }
                } catch (Exception e) {
                    if (errors.size() < 100) errors.add("Row " + lineNum + ": " + e.getMessage());
                    counters[1]++;
                }
            }
        }
    }

    // =========================================================================
    // Row parsing
    // =========================================================================

    private ParseResult parseRow(String[] cols, String[] headers,
                                  Map<String, Integer> colIndex, int rowNum) {
        if (cols.length < headers.length) {
            cols = Arrays.copyOf(cols, headers.length);
            for (int i = 0; i < cols.length; i++) if (cols[i] == null) cols[i] = "";
        }

        String shippingId = get(cols, colIndex, "shipping_id");
        if (shippingId == null || shippingId.isBlank())
            return ParseResult.error("shipping_id is blank — row skipped");

        String allocationDate = get(cols, colIndex, "allocation_date");
        if (allocationDate == null || allocationDate.isBlank()) {
            allocationDate = java.time.LocalDate.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH));
        }

        double lat = parseDouble(get(cols, colIndex, "drop_latitude"), 0.0);
        double lng = parseDouble(get(cols, colIndex, "drop_longitude"), 0.0);

        String warning = null;
        if (lat == 0.0 || lng == 0.0) {
            warning = "zero lat/lng";
        }

        boolean outOfRange = false;
        double distKm = 0.0;
        if (lat != 0.0 && lng != 0.0) {
            distKm = GoogleMapsService.haversine(hubLat, hubLng, lat, lng);
            if (distKm > maxDistanceKm) {
                outOfRange = true;
                String w = String.format("%.1f km from hub — out-of-range", distKm);
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
                .priority(normalizePriority(getOrDefault(cols, colIndex, "priority", "P2")))
                .outOfRange(outOfRange)
                .distanceFromHubKm(distKm)
                .build();

        // Always resolve pincode from GeoJSON boundary (overrides CSV value for consistency)
        if (lat != 0.0 && lng != 0.0 && pincodeBoundaryService.isLoaded()) {
            String resolved = pincodeBoundaryService.findPincodeForPoint(lat, lng);
            if (resolved != null) {
                s.setDropPincode(resolved);
            }
        }

        return warning != null ? ParseResult.withWarning(s, warning) : ParseResult.ok(s);
    }

    // =========================================================================
    // Validation
    // =========================================================================

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty())
            throw new IllegalArgumentException("No file uploaded or file is empty.");
        if (file.getSize() > MAX_FILE_SIZE_BYTES)
            throw new IllegalArgumentException(
                    "File too large: " + (file.getSize() / 1024 / 1024) + " MB. Max 20 MB.");
        String name = file.getOriginalFilename();
        if (name != null && !name.isBlank()) {
            String lower = name.toLowerCase();
            if (!lower.endsWith(".csv") && !lower.endsWith(".txt")
                    && !lower.endsWith(".xlsx") && !lower.endsWith(".xls"))
                throw new IllegalArgumentException(
                        "Unsupported file type: '" + name + "'. Accepted: .csv, .xlsx, .xls");
        }
    }

    private void validateRequiredColumns(Map<String, Integer> colIndex) {
        List<String> missing = new ArrayList<>();
        for (String req : REQUIRED_COLUMNS) {
            if (!colIndex.containsKey(req)) missing.add(req);
        }
        if (!missing.isEmpty()) {
            List<String> found = new ArrayList<>(colIndex.keySet());
            if (found.size() > 12) found = found.subList(0, 12);
            throw new IllegalArgumentException(
                    "Missing required columns: " + String.join(", ", missing) +
                    ". Found: " + String.join(", ", found) +
                    ". Tip: 'Latitude'/'lat' maps to drop_latitude, 'Shipment ID' maps to shipping_id.");
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String normalizeColName(String raw) {
        return raw.replaceAll("[^a-z0-9_]", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
    }

    private Map<String, Integer> buildColumnIndex(String[] headers) {
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < headers.length; i++) {
            if (headers[i] == null) continue;
            String name = normalizeColName(headers[i].trim().toLowerCase());
            if (name.isBlank()) continue;
            index.put(name, i);
            String canonical = COLUMN_ALIASES.get(name);
            if (canonical != null && !index.containsKey(canonical))
                index.put(canonical, i);
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

    /**
     * Normalise priority value from CSV to canonical P0/P1/P2.
     * Accepts: "P0","p0","0","HIGH" → "P0"; "P1","p1","1","MEDIUM" → "P1"; "P2","p2","2","LOW" → "P2"
     */
    private static String normalizePriority(String raw) {
        if (raw == null || raw.isBlank()) return "P2";
        String v = raw.trim().toUpperCase();
        return switch (v) {
            case "P0", "0", "HIGH", "URGENT", "CRITICAL" -> "P0";
            case "P1", "1", "MEDIUM", "NORMAL"           -> "P1";
            case "P2", "2", "LOW", "STANDARD"            -> "P2";
            default -> v.startsWith("P") ? v : "P2"; // keep P3+ as-is, unknown → P2
        };
    }

    private record ParseResult(Shipment shipment, String warning, String error) {
        static ParseResult ok(Shipment s)              { return new ParseResult(s, null, null); }
        static ParseResult withWarning(Shipment s, String w) { return new ParseResult(s, w, null); }
        static ParseResult error(String e)             { return new ParseResult(null, null, e); }
    }
}
