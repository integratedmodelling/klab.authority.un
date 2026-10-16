package org.integratedmodelling.klab.authority.un;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.util.*;
import java.util.regex.Pattern;

/** One SKOS scheme is one classification edition. Variants never compete for a latest alias. */
public record Classification(
    String id, String family, String version, String scheme, String label) {
  static final String QUERY =
      """
      PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
      SELECT DISTINCT ?scheme ?notation ?label ?version WHERE {
        ?scheme a skos:ConceptScheme .
        OPTIONAL { ?scheme skos:notation ?notation }
        OPTIONAL { ?scheme skos:prefLabel ?label FILTER(lang(?label) = 'en') }
        OPTIONAL { ?scheme <http://www.w3.org/2002/07/owl#versionInfo> ?version }
      } ORDER BY ?scheme LIMIT 2001
      """;

  static Map<String, Classification> catalogue(JsonNode rows) {
    if (rows.size() > 2000) throw new IllegalStateException("Caliper catalogue exceeds 2000 rows");
    var editions = new TreeMap<String, Classification>();
    for (var row : rows) {
      if (!"uri".equals(row.path("scheme").path("type").asText()))
        throw new IllegalStateException("Classification scheme must be an IRI");
      String uri = row.path("scheme").path("value").asText();
      SparqlClient.iri(uri);
      String family;
      String version = "";
      var m =
          Pattern.compile(
                  "/classifications/([A-Za-z0-9]+)/((?:v|rev)?[0-9]+(?:\\.[0-9]+)*)/([^/]+)$")
              .matcher(uri);
      if (m.find()) {
        family = m.group(1).toUpperCase(Locale.ROOT);
        version = m.group(2).replaceFirst("^(v|rev)", "");
        family +=
            switch (m.group(3)) {
              case "scheme", "core" -> "";
              case "ag" -> "_AG";
              case "FAO-fisheries" -> "_FI";
              default -> "_" + normalize(m.group(3));
            };
      } else if (uri.contains("/NOCS/")) {
        family =
            switch (uri.substring(uri.lastIndexOf('/') + 1)) {
              case "FAOmembers" -> "NOCS";
              case "nonFAO-members" -> "NOCS_NONFAO";
              case "territories" -> "NOCS_TERRITORIES";
              case "FAO-associate-members" -> "NOCS_ASSOCIATE";
              default -> "NOCS_" + normalize(uri.substring(uri.lastIndexOf('/') + 1));
            };
        String raw = row.path("version").path("value").asText();
        if (raw.matches("[0-9]{2}-[0-9]{4}"))
          version = raw.substring(3) + "." + Integer.parseInt(raw.substring(0, 2));
      } else if (uri.endsWith("/M49/scheme")) family = "M49";
      else if (uri.endsWith("/M49-FAO")) family = "M49_FAO";
      else if (uri.matches(".*/WCA[0-9]+/crops/scheme")) {
        family = "WCACROPS";
        version = uri.replaceFirst(".*/WCA([0-9]+)/crops/scheme", "$1");
      } else if (uri.matches(".*#ICATUS[0-9]+")) {
        family = "ICATUS";
        version = uri.substring(uri.lastIndexOf("#ICATUS") + 7);
      } else {
        family = normalize(row.path("notation").path("value").asText());
        String raw = row.path("version").path("value").asText();
        if (raw.matches("[0-9]+(\\.[0-9]+)*")) version = raw;
      }
      if (family.isEmpty() || !family.matches("[A-Z][A-Z0-9_]*"))
        throw new IllegalStateException("Cannot name Caliper scheme " + uri);
      String id = family + (version.isEmpty() ? "" : "_V" + version.replace('.', '_'));
      var classification =
          new Classification(
              id, family, version, uri, row.path("label").path("value").asText(family));
      var previous = editions.putIfAbsent(id, classification);
      if (previous != null && !previous.scheme.equals(uri))
        throw new IllegalStateException("Ambiguous classification identifier " + id);
    }
    var result = new TreeMap<>(editions);
    for (var c : editions.values()) {
      var latest = result.get(c.family);
      int comparison = latest == null ? 1 : compareVersions(c.version, latest.version);
      if (comparison > 0) result.put(c.family, c);
      else if (comparison == 0 && latest != null && !latest.scheme.equals(c.scheme))
        throw new IllegalStateException("Ambiguous latest classification " + c.family);
    }
    if (result.isEmpty()) throw new IllegalStateException("Caliper returned no classifications");
    return Collections.unmodifiableMap(result);
  }

  static int compareVersions(String left, String right) {
    String[] a = left.isEmpty() ? new String[0] : left.split("\\.");
    String[] b = right.isEmpty() ? new String[0] : right.split("\\.");
    for (int i = 0; i < Math.max(a.length, b.length); i++) {
      int c =
          new BigInteger(i < a.length ? a[i] : "0")
              .compareTo(new BigInteger(i < b.length ? b[i] : "0"));
      if (c != 0) return c;
    }
    return 0;
  }

  private static String normalize(String text) {
    return text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
  }
}
