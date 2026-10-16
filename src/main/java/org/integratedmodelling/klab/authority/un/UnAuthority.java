package org.integratedmodelling.klab.authority.un;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.integratedmodelling.klab.api.collections.Pair;
import org.integratedmodelling.klab.api.services.Authority;
import org.integratedmodelling.klab.api.services.reasoner.objects.AuthorityIdentitySyntax;
import org.integratedmodelling.klab.api.services.runtime.Notification;

/** Caliper classification identities. Each bridge selects exactly one SKOS ConceptScheme. */
@org.integratedmodelling.klab.api.services.reasoner.Authority(
    urn = UnAuthority.URN,
    version = "1.0.0",
    embeddable = true,
    searchable = true,
    subAuthorities = {
      "CPC",
      "CPC_AG",
      "CPC_FI",
      "ICC",
      "ISIC",
      "COICOP",
      "ICATUS",
      "FCL",
      "FPCD",
      "WCACROPS",
      "M49",
      "M49_FAO",
      "NOCS",
      "NOCS_NONFAO",
      "NOCS_TERRITORIES",
      "NOCS_ASSOCIATE"
    })
public final class UnAuthority implements Authority {
  public static final String URN = "klab.authority.un";
  public static final String M49_COUNTRY = "M49_COUNTRY";
  public static final String ENDPOINT = "https://caliper.integratedmodelling.org/caliper/sparql";
  private static final Set<String> PARAMETERS =
      Set.of(
          "urn",
          "catalog",
          "schemeUri",
          "endpoint",
          "timeoutSeconds",
          "searchLimit",
          "language",
          "semanticBoundaries");
  private final SparqlClient client;
  private final Map<String, Bridge> bridges;
  private final Map<String, Authority> views;
  private final Map<String, Classification> advertised;
  private final String catalog;

  public UnAuthority() {
    this(
        new SparqlClient(),
        new ConcurrentHashMap<>(),
        new ConcurrentHashMap<>(),
        bootstrap(),
        null);
  }

  private UnAuthority(
      SparqlClient client,
      Map<String, Bridge> bridges,
      Map<String, Authority> views,
      Map<String, Classification> advertised,
      String catalog) {
    this.client = client;
    this.bridges = bridges;
    this.views = views;
    this.advertised = advertised;
    this.catalog = catalog;
  }

  private static Map<String, Classification> bootstrap() {
    try (var input = UnAuthority.class.getResourceAsStream("/caliper-catalogue.json")) {
      if (input == null) throw new IllegalStateException("Missing Caliper catalogue resource");
      return new ConcurrentHashMap<>(
          Classification.catalogue(
              SparqlClient.JSON.readTree(input).path("results").path("bindings")));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Invalid Caliper catalogue resource", e);
    }
  }

  @Override
  public String getUrn() {
    return URN;
  }

  /**
   * Latest aliases and mutable schemes must never reuse persistent results from a previous load.
   */
  @Override
  public CachePolicy getCachePolicy() {
    return new CachePolicy("caliper-skos-2", 0, 0, 0);
  }

  @Override
  public Set<String> getSemanticBoundaries() {
    var supported = new HashSet<>(advertised.keySet());
    supported.add(M49_COUNTRY);
    return Set.copyOf(supported);
  }

  /**
   * A live discovery API; the bundled catalogue advertises initial names without startup traffic.
   */
  public Map<String, Classification> discoverClassifications() {
    var result =
        Classification.catalogue(
            client.select(
                SparqlClient.endpoint(ENDPOINT), Duration.ofSeconds(45), Classification.QUERY));
    advertised.putAll(result);
    return result;
  }

  @Override
  public String configure(ConfigurationRequest request) {
    var p = request.parameters();
    if (!URN.equals(p.get("urn")))
      throw new IllegalArgumentException("Wrong UN authority provider URN");
    for (var key : p.keySet())
      if (!PARAMETERS.contains(key))
        throw new IllegalArgumentException("Unknown UN parameter: " + key);
    int seconds = integer(p, "timeoutSeconds", 45, 1, 180);
    int limit = integer(p, "searchLimit", 25, 1, 100);
    String language = string(p.getOrDefault("language", "en"), "language").toLowerCase(Locale.ROOT);
    if (!language.matches("[a-z]{2,8}(-[a-z0-9]{1,8})*"))
      throw new IllegalArgumentException("Invalid language tag");
    if (p.containsKey("catalog") && p.containsKey("schemeUri"))
      throw new IllegalArgumentException("Select catalog or schemeUri, not both");
    String selected = p.containsKey("catalog") ? string(p.get("catalog"), "catalog") : catalog;
    if (selected == null && !p.containsKey("schemeUri"))
      throw new IllegalArgumentException("A classification catalog or schemeUri is required");
    if (catalog != null && selected != null && !catalog.equals(selected))
      throw new IllegalArgumentException("Conflicting sub-authority catalog");
    var endpoint = SparqlClient.endpoint(p.getOrDefault("endpoint", ENDPOINT));
    var timeout = Duration.ofSeconds(seconds);
    var live = Classification.catalogue(client.select(endpoint, timeout, Classification.QUERY));
    Classification classification;
    if (p.containsKey("schemeUri")) {
      if (catalog != null)
        throw new IllegalArgumentException("schemeUri cannot override a sub-authority");
      String uri = string(p.get("schemeUri"), "schemeUri");
      SparqlClient.iri(uri);
      classification =
          live.values().stream()
              .filter(c -> c.scheme().equals(uri))
              .findFirst()
              .orElseThrow(
                  () -> new IllegalArgumentException("Scheme is absent from Caliper: " + uri));
    } else {
      classification = live.get(selected);
      if (classification == null)
        throw new IllegalArgumentException("Classification is absent from Caliper: " + selected);
    }
    var supported = new HashSet<>(live.keySet());
    if (classification.family().equals("M49")) supported.add(M49_COUNTRY);
    request.requireSupportedBoundaries(supported);
    var vocabulary =
        new Vocabulary(
            classification,
            language,
            client.select(endpoint, timeout, Vocabulary.query(classification)));
    String id = UUID.randomUUID().toString();
    bridges.put(id, new Bridge(request, vocabulary, limit));
    advertised.putAll(live);
    return id;
  }

  @Override
  public void releaseConfiguration(String id) {
    if (id != null) bridges.remove(id);
  }

  @Override
  public Identity resolveIdentity(String configurationId, String identityId) {
    return resolveIdentity(configurationId, identityId, ResolutionMode.CONFIGURED);
  }

  @Override
  public Identity resolveIdentity(String configurationId, String identityId, ResolutionMode mode) {
    var bridge = bridge(configurationId);
    try {
      return identity(
          bridge, bridge.vocabulary.lookup(input(identityId)), 1, Objects.requireNonNull(mode));
    } catch (RuntimeException e) {
      return failure(bridge, identityId, e.getMessage());
    }
  }

  private Identity identity(Bridge bridge, Vocabulary.Term term, float score, ResolutionMode mode) {
    var v = bridge.vocabulary;
    var c = v.classification;
    Set<String> boundaries = term.member ? new HashSet<>(List.of(c.id(), c.family())) : Set.of();
    if (term.member && term.country && c.family().equals("M49")) boundaries.add(M49_COUNTRY);
    boolean deferred =
        mode == ResolutionMode.CONFIGURED
            && boundaries.stream().anyMatch(bridge.request.semanticBoundaries()::containsKey);
    if (!deferred) v.validateHierarchy(term);
    var result =
        new UnIdentity(
            term.uri,
            v.conceptName(term),
            bridge.request.name(),
            term.parents.isEmpty() ? bridge.request.rootIdentity() : null,
            new ArrayList<>(term.parents),
            v.description(term),
            v.label(term),
            score,
            AuthorityIdentitySyntax.encode(bridge.request.name(), term.uri),
            List.of(),
            boundaries);
    return new ClassifiedIdentity(
        result, boundaries, deferred ? HierarchyStatus.DEFERRED : HierarchyStatus.COMPLETE);
  }

  @Override
  public List<Identity> search(String query, String subAuthority, String configurationId) {
    var bridge = bridge(configurationId);
    if (subAuthority != null && !subAuthority.isBlank())
      throw new IllegalArgumentException(
          "UN subdivisions require their own classification configuration");
    String q = input(query);
    var v = bridge.vocabulary;
    return v.terms.values().stream()
        .filter(t -> t.member)
        .map(t -> new Hit(t, v.score(t, q)))
        .filter(h -> h.score > 0)
        .sorted(Comparator.comparingDouble((Hit h) -> -h.score).thenComparing(h -> h.term.uri))
        .limit(bridge.limit)
        .map(h -> identity(bridge, h.term, h.score, ResolutionMode.CONFIGURED))
        .toList();
  }

  /** Deterministic exact SKOS label reconciliation. Ambiguity is never resolved by result order. */
  @Override
  public Identity reconcile(String configurationId, Map<String, String> fields) {
    var bridge = bridge(configurationId);
    Objects.requireNonNull(fields, "Reconciliation fields are required");
    for (String key : fields.keySet())
      if (!Set.of("name", "notation", "uri", "language").contains(key))
        throw new IllegalArgumentException("Unknown reconciliation field: " + key);
    if (fields.keySet().stream().noneMatch(k -> Set.of("name", "notation", "uri").contains(k)))
      throw new IllegalArgumentException("name, notation or uri is required");
    fields.values().forEach(UnAuthority::input);
    try {
      var candidates = new ArrayList<>(bridge.vocabulary.terms.values());
      candidates.removeIf(t -> !t.member);
      if (fields.containsKey("uri")) candidates.removeIf(t -> !t.uri.equals(fields.get("uri")));
      if (fields.containsKey("notation"))
        candidates.removeIf(t -> !t.codes.contains(fields.get("notation")));
      if (fields.containsKey("name"))
        candidates.removeIf(
            t -> !bridge.vocabulary.nameMatches(t, fields.get("name"), fields.get("language")));
      if (candidates.size() != 1)
        return failure(
            bridge,
            fields.toString(),
            "Reconciliation matched "
                + candidates.size()
                + " concepts; explicit selection required");
      return identity(bridge, candidates.getFirst(), 1, ResolutionMode.CONFIGURED);
    } catch (RuntimeException e) {
      return failure(bridge, fields.toString(), e.getMessage());
    }
  }

  @Override
  public Authority subAuthority(String catalog) {
    if (catalog == null || catalog.isBlank()) return this;
    if (!advertised.containsKey(catalog))
      throw new IllegalArgumentException("Unknown UN classification: " + catalog);
    return views.computeIfAbsent(
        catalog, c -> new UnAuthority(client, bridges, views, advertised, c));
  }

  @Override
  public Capabilities getCapabilities() {
    return new Capabilities() {
      @Override
      public String getDescription() {
        return "UN-endorsed statistical classifications from Caliper; one SKOS scheme per bridge";
      }

      @Override
      public boolean isSearchable() {
        return true;
      }

      @Override
      public boolean isFuzzy() {
        return false;
      }

      @Override
      public boolean isReconciliationSupported() {
        return true;
      }

      @Override
      public boolean areSubAuthoritiesSearchFilters() {
        return false;
      }

      @Override
      public List<Pair<String, String>> getSubAuthorities() {
        return new TreeMap<>(advertised)
            .entrySet().stream().map(e -> Pair.of(e.getKey(), e.getValue().label())).toList();
      }

      @Override
      public List<String> getDocumentationFormats() {
        return List.of();
      }

      @Override
      public String getWorldview() {
        return null;
      }
    };
  }

  private Bridge bridge(String id) {
    var result = id == null ? null : bridges.get(id);
    if (result == null)
      throw new IllegalArgumentException("Unknown or released UN configuration ID");
    if (catalog != null
        && !List.of(
                result.vocabulary.classification.id(), result.vocabulary.classification.family())
            .contains(catalog))
      throw new IllegalArgumentException("Configuration belongs to another classification");
    return result;
  }

  private Identity failure(Bridge b, String id, String message) {
    return new UnIdentity(
        id,
        null,
        b.request.name(),
        null,
        List.of(),
        message,
        id,
        0,
        null,
        List.of(Notification.error("UN: " + message)),
        Set.of());
  }

  private static String string(Object value, String key) {
    if (!(value instanceof String s) || s.isBlank())
      throw new IllegalArgumentException(key + " must be a nonblank string");
    return s;
  }

  private static String input(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 2048
        || value.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException(
          "Identifier/query must contain 1 to 2048 characters without controls");
    return value;
  }

  private static int integer(Map<String, Object> p, String key, int fallback, int min, int max) {
    try {
      int value =
          new java.math.BigDecimal(String.valueOf(p.getOrDefault(key, fallback))).intValueExact();
      if (value < min || value > max) throw new ArithmeticException();
      return value;
    } catch (NumberFormatException | ArithmeticException e) {
      throw new IllegalArgumentException(
          key + " must be an integer between " + min + " and " + max);
    }
  }

  private record Bridge(ConfigurationRequest request, Vocabulary vocabulary, int limit) {}

  private record Hit(Vocabulary.Term term, float score) {}
}
