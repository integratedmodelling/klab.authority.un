package org.integratedmodelling.klab.authority.un;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Bounded, bridge-local snapshot. No RDF URI is dereferenced outside the configured endpoint. */
final class Vocabulary {
  static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
  static final String COUNTRY_EVIDENCE = "urn:klab:un:m49-country-evidence";
  static final int MAX_ROWS = 200000;
  final Map<String, Term> terms = new TreeMap<>();
  private final Map<String, Set<String>> notations = new HashMap<>();
  final Classification classification;
  private final String language;
  private final JsonNode frozenRows;

  Vocabulary(Classification classification, String language, JsonNode rows) {
    this.classification = classification;
    this.language = language;
    if (rows.size() > MAX_ROWS)
      throw new IllegalStateException("Classification exceeds " + MAX_ROWS + " rows");
    try {
      var canonical = SparqlClient.JSON.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
      var sorted = new TreeSet<String>();
      for (var row : rows) sorted.add(canonical.writeValueAsString(canonical.convertValue(row, Map.class)));
      var frozen = canonical.createArrayNode();
      for (var row : sorted) frozen.add(canonical.readTree(row));
      frozenRows = frozen;
    } catch (java.io.IOException failure) { throw new IllegalArgumentException("Cannot freeze vocabulary", failure); }
    for (var row : rows) {
      if (!"uri".equals(row.path("concept").path("type").asText()))
        throw new IllegalStateException("SKOS concept must be an IRI");
      String uri = row.path("concept").path("value").asText();
      SparqlClient.iri(uri);
      var term = terms.computeIfAbsent(uri, Term::new);
      String property = row.path("p").path("value").asText();
      var value = row.path("value");
      if (!value.path("value").isTextual()) throw new IllegalStateException("Missing RDF value");
      String text = value.path("value").asText();
      String lang = value.path("xml:lang").asText("").toLowerCase(Locale.ROOT);
      String type = value.path("type").asText();
      if (property.equals(COUNTRY_EVIDENCE)) {
        if (text.equals(UnAuthority.M49_COUNTRY) && type.equals("literal")) term.country = true;
      } else if (property.equals(SKOS + "inScheme")) {
        if (!type.equals("uri")) throw new IllegalStateException("SKOS scheme must be an IRI");
        if (text.equals(classification.scheme())) term.member = true;
      } else if (property.equals(SKOS + "broader")) {
        if (!type.equals("uri")) throw new IllegalStateException("SKOS parent must be an IRI");
        term.parents.add(text);
      } else if (property.equals(SKOS + "notation")) {
        if (!type.equals("literal") && !type.equals("typed-literal"))
          throw new IllegalStateException("SKOS notation must be a literal");
        term.codes.add(text);
      } else {
        if (!type.equals("literal") && !type.equals("typed-literal")) continue;
        term.values.computeIfAbsent(property, k -> new TreeSet<>()).add(new Text(lang, text));
      }
    }
    for (var term : terms.values())
      if (term.member)
        for (String code : term.codes)
          notations.computeIfAbsent(code, k -> new TreeSet<>()).add(term.uri);
    if (terms.isEmpty())
      throw new IllegalStateException("Selected classification contains no SKOS concepts");
    if (terms.values().stream().noneMatch(t -> t.member))
      throw new IllegalStateException("Snapshot omitted selected scheme membership");
  }

  JsonNode snapshotRows() { return frozenRows.deepCopy(); }

  static String query(Classification classification) {
    String scheme = SparqlClient.iri(classification.scheme());
    String countries =
        classification.family().equals("M49")
            ? """
          UNION {
            ?concept skos:inScheme %s ; skos:notation ?code .
            ?country a <https://stats.fao.org/ontologies/geopolitical#self_governing> ;
              <https://stats.fao.org/ontologies/geopolitical#codeM49> ?countryCode .
            FILTER(STR(?code) = STR(?countryCode))
            BIND(<urn:klab:un:m49-country-evidence> AS ?p)
            BIND("M49_COUNTRY" AS ?value)
          }
        """
                .formatted(scheme)
            : "";
    return """
        PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
        SELECT DISTINCT ?concept ?p ?value WHERE {
          { SELECT DISTINCT ?concept WHERE {
            ?member a skos:Concept ; skos:inScheme %s .
            ?member (skos:broader|^skos:narrower)* ?concept .
            ?concept a skos:Concept .
          } }
          { ?concept ?p ?value . FILTER(?p IN (
            skos:inScheme, skos:notation, skos:prefLabel, skos:altLabel, skos:hiddenLabel, skos:definition,
            skos:scopeNote, skos:note, skos:broader,
            <http://purl.org/dc/terms/description>, <http://purl.org/dc/elements/1.1/description>)) }
          UNION { ?value skos:narrower ?concept . BIND(skos:broader AS ?p) }
          %s
        } ORDER BY ?concept ?p ?value LIMIT %d
        """
        .formatted(scheme, countries, MAX_ROWS + 1);
  }

  Term lookup(String identifier) {
    var term = terms.get(identifier);
    if (term != null) return term;
    var matches = notations.getOrDefault(identifier, Set.of());
    if (matches.size() != 1)
      throw new IllegalArgumentException(
          matches.isEmpty()
              ? "No concept in selected classification: " + identifier
              : "Ambiguous SKOS notation; select a concept URI: " + identifier);
    return terms.get(matches.iterator().next());
  }

  /** Iterative DFS rejects missing parents and cycles without truncating an asserted hierarchy. */
  void validateHierarchy(Term start) {
    var active = new HashSet<String>();
    var done = new HashSet<String>();
    var stack = new ArrayDeque<Visit>();
    stack.push(new Visit(start.uri, false));
    while (!stack.isEmpty()) {
      var visit = stack.pop();
      if (visit.exit) {
        active.remove(visit.uri);
        done.add(visit.uri);
        continue;
      }
      if (done.contains(visit.uri)) continue;
      if (!active.add(visit.uri))
        throw new IllegalStateException("Cycle in SKOS hierarchy: " + visit.uri);
      if (active.size() > 512 || done.size() > 20000)
        throw new IllegalStateException("SKOS ancestry exceeds validation bounds");
      var term = terms.get(visit.uri);
      if (term == null)
        throw new IllegalStateException(
            "SKOS ancestor unavailable in Caliper snapshot: " + visit.uri);
      stack.push(new Visit(visit.uri, true));
      for (String parent : term.parents.descendingSet()) stack.push(new Visit(parent, false));
    }
  }

  String label(Term term) {
    return selected(term, SKOS + "prefLabel", term.codes.isEmpty() ? term.uri : term.codes.first());
  }

  String description(Term term) {
    var parts = new ArrayList<String>();
    parts.add(label(term));
    for (String p :
        List.of(
            SKOS + "definition",
            SKOS + "scopeNote",
            SKOS + "note",
            "http://purl.org/dc/terms/description",
            "http://purl.org/dc/elements/1.1/description")) {
      String value = selected(term, p, "");
      if (!value.isEmpty() && !parts.contains(value)) parts.add(value);
    }
    parts.add(
        "Classification: "
            + classification.label()
            + "\n\nScheme: "
            + classification.scheme()
            + "\n\nConcept: "
            + term.uri
            + (term.codes.isEmpty() ? "" : "\n\nCodes: " + String.join(", ", term.codes)));
    return String.join("\n\n", parts);
  }

  private String selected(Term term, String property, String fallback) {
    var values = term.values.getOrDefault(property, new TreeSet<>());
    for (String lang : List.of(language, "en", ""))
      for (var text : values) if (text.language.equals(lang)) return text.value;
    return values.isEmpty() ? fallback : values.first().value;
  }

  float score(Term term, String query) {
    String q = query.toLowerCase(Locale.ROOT);
    if (term.uri.equals(query) || term.codes.contains(query)) return 1;
    float score = 0;
    for (String p : List.of(SKOS + "prefLabel", SKOS + "altLabel", SKOS + "hiddenLabel")) {
      for (var text : term.values.getOrDefault(p, new TreeSet<>())) {
        String value = text.value.toLowerCase(Locale.ROOT);
        if (value.equals(q)) score = Math.max(score, p.equals(SKOS + "prefLabel") ? 1 : .95f);
        else if (value.contains(q)) score = Math.max(score, .6f);
      }
    }
    return score;
  }

  boolean nameMatches(Term term, String name, String matchLanguage) {
    for (String p : List.of(SKOS + "prefLabel", SKOS + "altLabel"))
      for (var text : term.values.getOrDefault(p, new TreeSet<>()))
        if ((matchLanguage == null || text.language.equalsIgnoreCase(matchLanguage))
            && text.value.equalsIgnoreCase(name)) return true;
    return false;
  }

  String conceptName(Term term) {
    try {
      return "UN_"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(
                          (classification.scheme() + "\n" + term.uri)
                              .getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  static final class Term {
    final String uri;
    boolean member;
    boolean country;
    final TreeSet<String> parents = new TreeSet<>();
    final TreeSet<String> codes = new TreeSet<>();
    final Map<String, TreeSet<Text>> values = new HashMap<>();

    Term(String uri) {
      this.uri = uri;
    }
  }

  private record Visit(String uri, boolean exit) {}

  private record Text(String language, String value) implements Comparable<Text> {
    @Override
    public int compareTo(Text other) {
      int c = language.compareTo(other.language);
      return c == 0 ? value.compareTo(other.value) : c;
    }
  }
}
