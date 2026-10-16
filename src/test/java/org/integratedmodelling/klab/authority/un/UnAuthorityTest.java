package org.integratedmodelling.klab.authority.un;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.integratedmodelling.klab.api.services.Authority.*;
import org.junit.jupiter.api.*;

class UnAuthorityTest {
  private HttpServer server;
  private UnAuthority authority;
  private String endpoint;
  private ArrayNode catalogue;
  private ArrayNode terms;
  private int status;
  private String lastQuery;
  private static final String S = "https://stats.fao.org/classifications/ICC/v1.1/scheme";
  private static final String ROOT = "https://stats.fao.org/classifications/ICC/v1.1/1";
  private static final String RYE = "https://stats.fao.org/classifications/ICC/v1.1/1-06";

  @BeforeEach void setup() throws Exception {
    authority = new UnAuthority();
    status = 200;
    catalogue = SparqlClient.JSON.createArrayNode();
    scheme("https://stats.fao.org/classifications/ICC/v1.0/scheme", "ICC10", "ICC 1.0");
    scheme(S, "ICC11", "ICC 1.1");
    terms = SparqlClient.JSON.createArrayNode();
    triple(ROOT, "inScheme", S, true, "");
    triple(RYE, "inScheme", S, true, "");
    triple(ROOT, "notation", "1", false, "");
    triple(ROOT, "prefLabel", "Cereals", false, "en");
    triple(RYE, "notation", "1.06", false, "");
    triple(RYE, "prefLabel", "Rye", false, "en");
    triple(RYE, "prefLabel", "Centeno", false, "es");
    triple(RYE, "altLabel", "Common rye", false, "en");
    triple(RYE, "broader", ROOT, true, "");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/sparql", exchange -> {
      lastQuery = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      assertEquals("POST", exchange.getRequestMethod());
      assertTrue(exchange.getRequestHeaders().getFirst("Content-Type").startsWith("application/sparql-query"));
      var rows = lastQuery.contains("?scheme") ? catalogue : terms.deepCopy();
      if (rows != catalogue) {
        var selection = java.util.regex.Pattern.compile("skos:inScheme <([^>]+)>").matcher(lastQuery);
        assertTrue(selection.find());
        for (var row : rows) if (row.path("p").path("value").asText().equals(Vocabulary.SKOS + "inScheme")
            && row.path("value").path("value").asText().equals(S))
          ((ObjectNode) row.path("value")).put("value", selection.group(1));
      }
      var json = SparqlClient.JSON.createObjectNode();
      json.putObject("head").putArray("vars").add("concept");
      json.putObject("results").set("bindings", rows);
      byte[] body = SparqlClient.JSON.writeValueAsBytes(json);
      exchange.getResponseHeaders().set("Content-Type", "application/sparql-results+json");
      exchange.sendResponseHeaders(status, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/sparql";
  }
  @AfterEach void cleanup() { server.stop(0); }
  private void scheme(String uri, String notation, String label) {
    var row = catalogue.addObject();
    binding(row, "scheme", uri, true, ""); binding(row, "notation", notation, false, ""); binding(row, "label", label, false, "en");
  }
  private void triple(String uri, String property, String value, boolean iri, String language) {
    var row = terms.addObject();
    binding(row, "concept", uri, true, ""); binding(row, "p", Vocabulary.SKOS + property, true, ""); binding(row, "value", value, iri, language);
  }
  private void binding(ObjectNode row, String key, String value, boolean iri, String language) {
    var b = row.putObject(key).put("type", iri ? "uri" : "literal").put("value", value);
    if (!language.isEmpty()) b.put("xml:lang", language);
  }
  private ConfigurationRequest request(String name, String root, Map<String, Object> extra) {
    var p = new HashMap<String, Object>();
    p.put("urn", UnAuthority.URN); p.put("endpoint", endpoint); p.put("catalog", "ICC"); p.putAll(extra);
    return new ConfigurationRequest("test.worldview", name, root, p);
  }
  private String configure() { return authority.configure(request("CROPS", "test:Crop", Map.of())); }

  @Test void canonicalUriNotationHierarchyAndLocalNames() {
    String id = configure();
    assertTrue(lastQuery.contains("<" + S + ">"));
    var rye = authority.resolveIdentity(id, "1.06");
    assertTrue(rye.getNotifications().isEmpty());
    assertEquals(RYE, rye.getId());
    assertEquals(List.of(ROOT), rye.getParentIds());
    assertNull(rye.getBaseIdentity());
    assertEquals("CROPS", rye.getAuthorityName());
    assertEquals("CROPS:[" + RYE + "]", rye.getLocator());
    assertEquals("test:Crop", authority.resolveIdentity(id, ROOT).getBaseIdentity());
    assertEquals(rye.getConceptName(), authority.resolveIdentity(id, RYE).getConceptName());
    assertNotEquals(rye.getConceptName(), authority.resolveIdentity(id, ROOT).getConceptName());
    assertFalse(authority.resolveIdentity(id, "Rye").getNotifications().isEmpty());
  }
  @Test void countryBoundaryDoesNotClassifyRegionsOrUnattestedLeaves() {
    scheme("https://unstats.un.org/classifications/geo/M49/scheme", "M49-UNSD", "M49");
    // The fixture supplies the evidence row produced only by the authoritative geopolitical join.
    var row = terms.addObject();
    binding(row, "concept", RYE, true, "");
    binding(row, "p", Vocabulary.COUNTRY_EVIDENCE, true, "");
    binding(row, "value", UnAuthority.M49_COUNTRY, false, "");
    triple("https://example.org/unattested", "inScheme", S, true, "");
    triple("https://example.org/unattested", "prefLabel", "Unattested leaf", false, "en");
    String id = authority.configure(request("UN.M49", "geography:GeographicalIdentity", Map.of(
        "catalog", "M49", "semanticBoundaries", Map.of("M49_COUNTRY", "geography:Country"))));
    var country = authority.resolveIdentity(id, RYE);
    assertEquals(Set.of("M49", "M49_COUNTRY"), country.getSemanticBoundaries());
    assertEquals(HierarchyStatus.DEFERRED, country.getHierarchyStatus());
    assertTrue(country.getParentIds().isEmpty());
    var region = authority.resolveIdentity(id, ROOT);
    assertEquals(Set.of("M49"), region.getSemanticBoundaries());
    assertEquals(HierarchyStatus.COMPLETE, region.getHierarchyStatus());
    assertEquals(Set.of("M49"), authority.resolveIdentity(id, "https://example.org/unattested").getSemanticBoundaries());
    var full = authority.resolveIdentity(id, RYE, ResolutionMode.FULL_HIERARCHY);
    assertEquals(HierarchyStatus.COMPLETE, full.getHierarchyStatus());
    assertEquals(List.of(ROOT), full.getParentIds());
    assertThrows(IllegalArgumentException.class, () -> authority.configure(request("CROPS", "test:Crop", Map.of(
        "semanticBoundaries", Map.of("M49_COUNTRY", "geography:Country")))));
  }
  @Test void snapshotsAndBridgeStateAreIndependent() {
    String a = configure();
    String b = authority.configure(request("OTROS", "test:Other", Map.of("language", "es")));
    terms.removeAll();
    assertEquals("Rye", authority.resolveIdentity(a, RYE).getLabel());
    assertEquals("Centeno", authority.resolveIdentity(b, RYE).getLabel());
    assertEquals("test:Other", authority.resolveIdentity(b, ROOT).getBaseIdentity());
    authority.releaseConfiguration(a);
    assertThrows(IllegalArgumentException.class, () -> authority.resolveIdentity(a, RYE));
    assertEquals(RYE, authority.resolveIdentity(b, RYE).getId());
    assertEquals(0, authority.getCachePolicy().identitySeconds());
  }
  @Test void searchAndReconciliationRequireExplicitUnambiguousSelection() {
    String id = configure();
    var hits = authority.search("rye", null, id);
    assertEquals(1, hits.size());
    assertEquals(RYE, hits.getFirst().getId());
    assertEquals(RYE, authority.resolveIdentity(id, hits.getFirst().getId()).getId());
    assertEquals(RYE, authority.reconcile(id, Map.of("name", "Common rye", "language", "en")).getId());
    assertTrue(authority.search("potato", null, id).isEmpty());
    assertFalse(authority.reconcile(id, Map.of("name", "no match")).getNotifications().isEmpty());
    triple(ROOT, "altLabel", "Common rye", false, "en");
    String ambiguous = configure();
    assertFalse(authority.reconcile(ambiguous, Map.of("name", "Common rye")).getNotifications().isEmpty());
    assertEquals(RYE, authority.reconcile(ambiguous, Map.of("name", "Common rye", "notation", "1.06")).getId());
    assertThrows(IllegalArgumentException.class, () -> authority.search("rye", "ICC", id));
  }
  @Test void cyclesMissingParentsAndAmbiguousNotationsFailClosed() {
    triple(ROOT, "broader", RYE, true, "");
    String cycle = configure();
    assertFalse(authority.resolveIdentity(cycle, RYE).getNotifications().isEmpty());
    terms.remove(terms.size() - 1);
    triple(ROOT, "broader", "https://example.org/foreign", true, "");
    String missing = configure();
    assertFalse(authority.resolveIdentity(missing, RYE).getNotifications().isEmpty());
    terms.remove(terms.size() - 1);
    triple(ROOT, "notation", "1.06", false, "");
    String ambiguous = configure();
    assertFalse(authority.resolveIdentity(ambiguous, "1.06").getNotifications().isEmpty());
    assertEquals(RYE, authority.resolveIdentity(ambiguous, RYE).getId());
  }
  @Test void multipleParentsRemainParentsAndOnlyRootsUseWorldviewAnchor() {
    triple("https://example.org/second", "prefLabel", "Second root", false, "en");
    triple(RYE, "broader", "https://example.org/second", true, "");
    var rye = authority.resolveIdentity(configure(), RYE);
    assertTrue(rye.getNotifications().isEmpty());
    assertEquals(2, rye.getParentIds().size());
    assertNull(rye.getBaseIdentity());
  }
  @Test void externalAncestorsSupportHierarchyWithoutBecomingClassificationMembers() {
    String support = "https://example.org/core-parent";
    triple(ROOT, "broader", support, true, "");
    triple(support, "prefLabel", "Outside core", false, "en");
    triple(support, "notation", "CORE", false, "");
    triple(support, "inScheme", "https://example.org/core-scheme", true, "");
    String id = configure();
    assertTrue(authority.resolveIdentity(id, RYE).getNotifications().isEmpty());
    assertTrue(authority.resolveIdentity(id, support).getSemanticBoundaries().isEmpty());
    assertTrue(authority.search("Outside", null, id).isEmpty());
    assertFalse(authority.reconcile(id, Map.of("uri", support)).getNotifications().isEmpty());
    assertFalse(authority.resolveIdentity(id, "CORE").getNotifications().isEmpty());
    String mapped = authority.configure(request("CROPS", "test:Crop", Map.of("semanticBoundaries", Map.of("ICC", "test:CropType"))));
    assertEquals(HierarchyStatus.COMPLETE, authority.resolveIdentity(mapped, support).getHierarchyStatus());
  }
  @Test void boundariesAreEstablishedBySchemeMembershipAndFullResolutionValidatesAncestry() {
    triple(ROOT, "broader", RYE, true, "");
    String id = authority.configure(request("CROPS", "test:Crop", Map.of("semanticBoundaries", Map.of("ICC", "test:CropType"))));
    var deferred = authority.resolveIdentity(id, "1.06");
    assertTrue(deferred.getNotifications().isEmpty());
    assertEquals(HierarchyStatus.DEFERRED, deferred.getHierarchyStatus());
    assertEquals(Set.of("ICC", "ICC_V1_1"), deferred.getSemanticBoundaries());
    assertTrue(deferred.getParentIds().isEmpty());
    assertNull(deferred.getBaseIdentity());
    assertFalse(authority.resolveIdentity(id, RYE, ResolutionMode.FULL_HIERARCHY).getNotifications().isEmpty());
    assertThrows(IllegalArgumentException.class, () -> authority.configure(request("CROPS", "test:Crop", Map.of("semanticBoundaries", Map.of("BOGUS", "test:CropType")))));
  }
  @Test void latestUsesNumericVersionOrderingAndPinnedViewsRemainAvailable() {
    scheme("https://stats.fao.org/classifications/ICC/v1.9/scheme", "ICC19", "ICC 1.9");
    scheme("https://stats.fao.org/classifications/ICC/v1.10/scheme", "ICC110", "ICC 1.10");
    configure();
    assertTrue(lastQuery.contains("/v1.10/scheme>"));
    authority.configure(request("OLD", "test:Old", Map.of("catalog", "ICC_V1_0")));
    assertTrue(lastQuery.contains("/v1.0/scheme>"));
    var view = authority.subAuthority("ICC_V1_0");
    var p = new HashMap<String, Object>(); p.put("urn", UnAuthority.URN); p.put("endpoint", endpoint);
    String id = view.configure(new ConfigurationRequest("test.worldview", "OLD", "test:Old", p));
    assertEquals(RYE, view.resolveIdentity(id, RYE).getId());
    assertSame(view, authority.subAuthority("ICC_V1_0"));
    assertFalse(authority.getCapabilities().areSubAuthoritiesSearchFilters());
  }
  @Test void bootstrapSeparatesVariantsAndDuplicateNocsNotations() {
    var names = authority.getSemanticBoundaries();
    assertTrue(names.containsAll(Set.of("CPC", "CPC_V2_0", "CPC_V2_1", "CPC_AG", "CPC_FI", "NOCS", "NOCS_TERRITORIES", "NOCS_NONFAO", "NOCS_ASSOCIATE")));
    assertEquals(0, Classification.compareVersions("1.0", "1"));
    assertTrue(Classification.compareVersions("10", "2") > 0);
  }
  @Test void configurationAndTransportFailuresNeverBecomeEmptyMatches() {
    assertThrows(IllegalArgumentException.class, () -> authority.configure(request("CROPS", "test:Crop", Map.of("unknown", true))));
    assertThrows(IllegalArgumentException.class, () -> authority.configure(request("CROPS", "test:Crop", Map.of("catalog", "ABSENT"))));
    assertThrows(IllegalArgumentException.class, () -> SparqlClient.iri("https://example.org/> ?s ?p ?o"));
    assertThrows(IllegalArgumentException.class, () -> SparqlClient.endpoint("http://example.org/sparql"));
    status = 503;
    assertThrows(IllegalStateException.class, this::configure);
    status = 200; catalogue.removeAll();
    assertThrows(IllegalStateException.class, this::configure);
  }
}
