package pl.databucket.perf;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The test data: orders, the same for prepareData and scenario 1. Row n gets (as in the 1M-row measurement):
 * customer "customer-(n % 50000)" (20 rows per customer in 1M), status NEW 5 % / PAID 60 % / SHIPPED 30 % /
 * CANCELLED 5 %, total 0-1000, city one of six, email, address, two items.
 * <p>
 * The scenarios change only {@code $.perf}, never the fields used by the rules, so the data doesn't run out.
 */
public final class Orders {

    private static final List<String> CITIES = List.of("Warszawa", "Krakow", "Gdansk", "Wroclaw", "Poznan", "Lodz");

    private Orders() {
    }

    public static Map<String, Object> properties(long n) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long m = n % 20;
        String status = m == 0 ? "NEW" : m < 13 ? "PAID" : m < 19 ? "SHIPPED" : "CANCELLED";
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("customer", "customer-" + (n % 50_000));
        p.put("status", status);
        p.put("total", BigDecimal.valueOf(random.nextDouble(1000)).setScale(2, RoundingMode.HALF_UP));
        p.put("city", CITIES.get((int) (n % CITIES.size())));
        p.put("email", "user" + n + "@example.com");
        p.put("address", Map.of("street", "Street " + (n % 997), "zip", String.format("%05d", n % 99_999)));
        p.put("items", List.of("sku-" + (n % 300), "sku-" + ((n * 7) % 300)));
        return p;
    }
}
