package com.example.LMrouting.service;

import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class DataLoaderService implements CommandLineRunner {

    private final ShipmentRepository shipmentRepository;

    @Override
    public void run(String... args) {
        if (shipmentRepository.count() > 0) {
            log.info("Data already loaded, skipping.");
            return;
        }

        try {
            ClassPathResource resource = new ClassPathResource("data/shipments.csv");
            loadFromCsv(resource.getInputStream());
        } catch (Exception e) {
            log.warn("CSV file not found, loading sample data from embedded dataset.");
            loadSampleData();
        }
    }

    private void loadFromCsv(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        String header = reader.readLine(); // skip header
        String line;
        List<Shipment> shipments = new ArrayList<>();

        while ((line = reader.readLine()) != null) {
            try {
                String[] cols = line.split(",", -1);
                if (cols.length < 17) continue;

                Shipment s = Shipment.builder()
                        .hubName(cols[0].trim())
                        .allocationDate(cols[1].trim())
                        .shippingId(cols[2].trim())
                        .dropPincode(cols[3].trim())
                        .cityName(cols[4].trim())
                        .stateName(cols[5].trim())
                        .shipmentFlow(cols[6].trim())
                        .isHeavy(parseIntSafe(cols[7]))
                        .phyWeight(parseDoubleSafe(cols[8]))
                        .volWeight(parseDoubleSafe(cols[9]))
                        .orderType(cols[10].trim())
                        .dropLatitude(parseDoubleSafe(cols[11]))
                        .dropLongitude(parseDoubleSafe(cols[12]))
                        .clientId(cols[13].trim())
                        .srName(cols.length > 14 ? cols[14].trim() : "")
                        .runNumber(cols.length > 15 ? parseIntSafe(cols[15]) : 0)
                        .shipmentConversion(cols.length > 16 ? cols[16].trim() : "")
                        .rate(cols.length > 17 ? parseDoubleSafe(cols[17]) : 0)
                        .expectedPayout(cols.length > 18 ? parseDoubleSafe(cols[18]) : 0)
                        .build();

                if (s.getDropLatitude() != 0 && s.getDropLongitude() != 0) {
                    shipments.add(s);
                }
            } catch (Exception e) {
                log.debug("Skipping malformed line: {}", e.getMessage());
            }
        }

        shipmentRepository.saveAll(shipments);
        log.info("Loaded {} shipments from CSV", shipments.size());
    }

    /**
     * Load a representative sample from the attached Excel data.
     * This covers the key pincodes and SR names from the dataset.
     */
    private void loadSampleData() {
        List<Shipment> shipments = new ArrayList<>();

        // Sample data extracted from the provided Excel - 24-Mar-26 date
        // Hub: PNQ HDP at 18.4600561, 73.8884305
        String date = "24-Mar-26";
        String hub = "PNQ HDP";

        // Pincode 411048 shipments (largest cluster)
        addShipment(shipments, hub, date, "151697151403159", "411040", 18.4874979674, 73.8970740535, "101", "195636", "COD");
        addShipment(shipments, hub, date, "151697159108524", "411040", 18.4876990097, 73.8968484029, "101", "195636", "COD");
        addShipment(shipments, hub, date, "152980550988339", "411048", 18.4735314917, 73.8919759917, "102", "884679", "COD");
        addShipment(shipments, hub, date, "152785861433573", "411060", 18.4780659315, 73.9162320658, "103", "1156205", "COD");
        addShipment(shipments, hub, date, "153291460701980", "411048", 18.456692562, 73.8896148186, "104", "854322", "COD");
        addShipment(shipments, hub, date, "1323154782919", "411048", 18.4687358931, 73.8877570071, "105", "913823", "COD");
        addShipment(shipments, hub, date, "14344960786098", "411048", 18.4467745081, 73.9066992375, "106", "854322", "COD");
        addShipment(shipments, hub, date, "13409649196864", "411048", 18.4720183833, 73.8934463167, "107", "884679", "COD");
        addShipment(shipments, hub, date, "1367065185948", "411028", 18.4682033862, 73.8880691281, "108", "913823", "COD");
        addShipment(shipments, hub, date, "13409629202849", "411048", 18.458970076, 73.8882776993, "107", "1070125", "COD");
        addShipment(shipments, hub, date, "1392760008824", "411048", 18.4657647, 73.889762, "109", "1070125", "COD");
        addShipment(shipments, hub, date, "1340964812187", "411048", 18.4710606479, 73.8834792447, "107", "913823", "COD");
        addShipment(shipments, hub, date, "13409639254385", "411060", 18.468212084, 73.9195106723, "107", "1171410", "COD");
        addShipment(shipments, hub, date, "1340965640023", "411060", 18.4751462, 73.9163246, "107", "1156205", "COD");
        addShipment(shipments, hub, date, "13409641173680", "411001", 18.5122303, 73.8859975, "107", "884679", "COD");
        addShipment(shipments, hub, date, "14396453297645", "411048", 18.475045639, 73.8962941987, "110", "884679", "COD");
        addShipment(shipments, hub, date, "153793950013843", "411048", 18.4689658376, 73.8855645514, "111", "913823", "COD");
        addShipment(shipments, hub, date, "13409636270834", "411048", 18.464948719, 73.8937394211, "107", "1070125", "PrePaid");
        addShipment(shipments, hub, date, "13409643358021", "411048", 18.4745633242, 73.892278596, "107", "884679", "COD");
        addShipment(shipments, hub, date, "153793950014306", "411048", 18.4604742302, 73.8765813112, "111", "1134723", "COD");
        addShipment(shipments, hub, date, "153291460801688", "411048", 18.4471695924, 73.8963331237, "104", "854322", "COD");
        addShipment(shipments, hub, date, "14396453279553", "411048", 18.4745554551, 73.8921113403, "110", "884679", "COD");
        addShipment(shipments, hub, date, "14112361389304", "411048", 18.4662008872, 73.8766908657, "112", "1134723", "COD");
        addShipment(shipments, hub, date, "1340962039502", "411048", 18.4802969991, 73.8956494451, "107", "1045713", "COD");
        addShipment(shipments, hub, date, "13409626165007", "411048", 18.4624683286, 73.8824968214, "107", "1134723", "COD");
        addShipment(shipments, hub, date, "13409618950213", "411048", 18.4601283668, 73.8879485935, "107", "1070125", "COD");
        addShipment(shipments, hub, date, "13409651613845", "411048", 18.4650337392, 73.8799456396, "107", "913823", "COD");
        addShipment(shipments, hub, date, "13409654303100", "411060", 18.4447619, 73.9167266, "107", "1077324", "COD");
        addShipment(shipments, hub, date, "153912150079911", "411060", 18.4500897164, 73.9085542574, "113", "1085589", "COD");
        addShipment(shipments, hub, date, "14112361396987", "411048", 18.4744598569, 73.8931978352, "112", "884679", "COD");
        addShipment(shipments, hub, date, "13409629829963", "411046", 18.4597837971, 73.8774976544, "107", "1134723", "COD");
        addShipment(shipments, hub, date, "152980551002504", "411048", 18.4762207896, 73.8932059216, "102", "884679", "COD");
        addShipment(shipments, hub, date, "13409630322995", "411048", 18.4754209407, 73.8935080264, "107", "884679", "COD");
        addShipment(shipments, hub, date, "14344960918396", "411048", 18.4694358497, 73.8799656508, "106", "913823", "COD");
        addShipment(shipments, hub, date, "153291460709410", "411048", 18.4641, 73.89264, "104", "1070125", "COD");
        addShipment(shipments, hub, date, "14344960919351", "411048", 18.45898, 73.89208, "106", "1070125", "COD");
        addShipment(shipments, hub, date, "14344960923503", "411048", 18.4724840698, 73.8948609875, "106", "884679", "COD");
        addShipment(shipments, hub, date, "13409638841000", "411048", 18.46898422, 73.8832066933, "107", "913823", "COD");
        addShipment(shipments, hub, date, "1367066156195", "411028", 18.4418095425, 73.9040841372, "108", "1085589", "COD");
        addShipment(shipments, hub, date, "13409647755208", "411048", 18.4685307069, 73.8825530044, "107", "913823", "COD");
        addShipment(shipments, hub, date, "13409613100989", "411060", 18.45964601, 73.9152052328, "107", "836515", "COD");
        addShipment(shipments, hub, date, "13409643917115", "411048", 18.4748774513, 73.8933970937, "107", "884679", "PrePaid");
        addShipment(shipments, hub, date, "13409650920636", "411060", 18.4650221845, 73.9203321163, "107", "1171410", "COD");
        addShipment(shipments, hub, date, "13409653987478", "411048", 18.4638167324, 73.8883834729, "107", "1070125", "COD");
        addShipment(shipments, hub, date, "14344960946181", "411048", 18.4679688171, 73.8986864282, "106", "1045713", "COD");
        addShipment(shipments, hub, date, "13409638469788", "411018", 18.4716555006, 73.8834868227, "107", "1069856", "COD");
        addShipment(shipments, hub, date, "153291460806659", "411048", 18.468157, 73.8911465, "104", "884679", "COD");
        addShipment(shipments, hub, date, "13409647556874", "411048", 18.4580465016, 73.8779913052, "107", "1134723", "COD");
        addShipment(shipments, hub, date, "153793950018493", "411048", 18.4734995046, 73.8946148192, "111", "884679", "COD");
        addShipment(shipments, hub, date, "14131752756494", "411022", 18.4684598673, 73.8810479475, "114", "913823", "COD");

        // Pincode 411060 shipments (second largest)
        addShipment(shipments, hub, date, "153927360031804", "411060", 18.44339, 73.90679, "121", "1085589", "COD");
        addShipment(shipments, hub, date, "125050386536", "411060", 18.4406645016, 73.90784154, "122", "1085589", "COD");
        addShipment(shipments, hub, date, "14591760959604", "411060", 18.480813658, 73.9128786035, "117", "1156205", "COD");
        addShipment(shipments, hub, date, "14461960743407", "411060", 18.4740045437, 73.9178650113, "115", "1156205", "COD");
        addShipment(shipments, hub, date, "153508460059126", "411060", 18.4763729683, 73.9089272345, "123", "1045713", "COD");
        addShipment(shipments, hub, date, "153431053296677", "411060", 18.4629664871, 73.9077778226, "128", "1159729", "COD");
        addShipment(shipments, hub, date, "1367067104883", "411060", 18.481792925, 73.909935575, "108", "1156205", "COD");
        addShipment(shipments, hub, date, "14591760936466", "411060", 18.4520056468, 73.9150023116, "117", "836515", "COD");
        addShipment(shipments, hub, date, "14461960738525", "411061", 18.4676153673, 73.9224352842, "115", "1171410", "COD");

        // Pincode 411040 shipments
        addShipment(shipments, hub, date, "13409655385947", "411040", 18.48527, 73.89314, "107", "1123675", "COD");
        addShipment(shipments, hub, date, "14461960694683", "411040", 18.492095, 73.9001776, "115", "1123675", "COD");
        addShipment(shipments, hub, date, "153799460020919", "411040", 18.4821748836, 73.9012605584, "116", "221644", "COD");
        addShipment(shipments, hub, date, "14344960962708", "411040", 18.4915665178, 73.9013039534, "106", "992948", "COD");
        addShipment(shipments, hub, date, "153768760120462", "411040", 18.4961920217, 73.8967260275, "132", "195636", "COD");
        addShipment(shipments, hub, date, "153619240129093", "411040", 18.4920676804, 73.8963725561, "145", "992948", "COD");
        addShipment(shipments, hub, date, "1340551726527", "411040", 18.4910829546, 73.9003818524, "134", "195636", "COD");
        addShipment(shipments, hub, date, "14136851149295", "411040", 18.487414994, 73.8848127314, "144", "1123675", "COD");
        addShipment(shipments, hub, date, "152041840304411", "411040", 18.4823637236, 73.9052754057, "130", "221644", "COD");
        addShipment(shipments, hub, date, "151705390190223", "411040", 18.4845047838, 73.9071251527, "133", "221644", "COD");

        // More 411028 shipments
        addShipment(shipments, hub, date, "13409617143105", "411028", 18.4823926369, 73.9121332487, "107", "1156205", "COD");
        addShipment(shipments, hub, date, "14187460145995", "411028", 18.456868425, 73.88866765, "119", "1070125", "COD");

        // 411022 shipments
        addShipment(shipments, hub, date, "153291460901379", "411022", 18.4862972734, 73.9078025139, "104", "221644", "COD");

        // 411037 shipments
        addShipment(shipments, hub, date, "1318360266028", "411037", 18.4689905995, 73.882973087, "125", "913823", "COD");

        shipmentRepository.saveAll(shipments);
        log.info("Loaded {} sample shipments", shipments.size());
    }

    private void addShipment(List<Shipment> list, String hub, String date, String shippingId,
                             String pincode, double lat, double lng, String clientId,
                             String srName, String orderType) {
        list.add(Shipment.builder()
                .hubName(hub)
                .allocationDate(date)
                .shippingId(shippingId)
                .dropPincode(pincode)
                .cityName("PUNE")
                .stateName("MAHARASHTRA")
                .shipmentFlow("Forward")
                .isHeavy(0)
                .phyWeight(0.5)
                .volWeight(0.1)
                .orderType(orderType)
                .dropLatitude(lat)
                .dropLongitude(lng)
                .clientId(clientId)
                .srName(srName)
                .runNumber(3)
                .shipmentConversion("75%")
                .rate(18.4)
                .expectedPayout(13.8)
                .build());
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private double parseDoubleSafe(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0.0; }
    }
}
