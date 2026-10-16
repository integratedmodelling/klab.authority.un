package org.integratedmodelling.klab.authority.un;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.integratedmodelling.klab.api.services.Authority.ConfigurationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Explicit opt-in: uses only the Integrated Modelling Caliper endpoint. */
@EnabledIfSystemProperty(named = "caliper.live", matches = "true")
class UnAuthorityLiveTest {
  @Test void documentedCountryBoundaryClassifiesSpainAndExcludesEurope() {
    var authority = new UnAuthority();
    String id = authority.configure(new ConfigurationRequest("test.live", "UN.M49", "geography:GeographicalIdentity",
        Map.of("urn", UnAuthority.URN, "catalog", "M49", "semanticBoundaries",
            Map.of("M49_COUNTRY", "geography:Country"))));
    try {
      String spain = "https://unstats.un.org/classifications/geo/M49/724";
      var country = authority.reconcile(id, Map.of("name", "Spain", "language", "en", "uri", spain));
      assertTrue(country.getNotifications().isEmpty(), country.getDescription());
      assertTrue(country.getSemanticBoundaries().contains("M49_COUNTRY"));
      assertTrue(authority.resolveIdentity(id, "https://unstats.un.org/classifications/geo/M49/M49-UNSD#724")
          .getSemanticBoundaries().contains("M49_COUNTRY"));
      assertFalse(authority.reconcile(id, Map.of("name", "Spain", "language", "en")).getNotifications().isEmpty());
      assertFalse(authority.resolveIdentity(id, "724").getNotifications().isEmpty());
      assertEquals(org.integratedmodelling.klab.api.services.Authority.HierarchyStatus.DEFERRED, country.getHierarchyStatus());
      var europe = authority.resolveIdentity(id, "https://unstats.un.org/classifications/geo/M49/150");
      assertTrue(europe.getNotifications().isEmpty(), europe.getDescription());
      assertFalse(europe.getSemanticBoundaries().contains("M49_COUNTRY"));
      assertFalse(authority.resolveIdentity(id, spain,
          org.integratedmodelling.klab.api.services.Authority.ResolutionMode.FULL_HIERARCHY).getParentIds().isEmpty());
    } finally { authority.releaseConfiguration(id); }
  }
  @Test void auditEveryHostedScheme() {
    var client = new SparqlClient();
    var authority = new UnAuthority();
    var schemes = authority.discoverClassifications().values().stream().distinct().toList();
    for (var scheme : schemes) {
      var rows = client.select(SparqlClient.endpoint(UnAuthority.ENDPOINT), java.time.Duration.ofSeconds(90), Vocabulary.query(scheme));
      var vocabulary = new Vocabulary(scheme, "en", rows);
      int invalid = 0;
      String example = "";
      for (var term : vocabulary.terms.values()) {
        try { vocabulary.validateHierarchy(term); }
        catch (IllegalStateException e) { invalid++; example = e.getMessage(); }
      }
      System.out.println("SCHEME " + scheme.id() + " terms=" + vocabulary.terms.size() + " invalidAncestry=" + invalid + (invalid == 0 ? "" : " example=" + example));
      assertEquals(0, invalid, scheme.id() + ": " + example);
    }
  }
  @Test void resolveSearchAndReconcileLiveIcc() {
    var authority = new UnAuthority();
    var catalogues = authority.discoverClassifications();
    assertTrue(catalogues.containsKey("ICC"));
    assertTrue(catalogues.containsKey("ICC_V1_0"));
    String id = authority.configure(new ConfigurationRequest("test.live", "CROPS", "test:Crop",
        Map.of("urn", UnAuthority.URN, "catalog", "ICC", "timeoutSeconds", 90)));
    try {
      var rye = authority.resolveIdentity(id, "1.06");
      assertTrue(rye.getNotifications().isEmpty(), () -> rye.getDescription());
      assertEquals("https://stats.fao.org/classifications/ICC/v1.1/1-06", rye.getId());
      assertEquals("Rye", rye.getLabel());
      assertFalse(rye.getParentIds().isEmpty());
      var search = authority.search("Rye", null, id);
      assertTrue(search.stream().anyMatch(i -> i.getId().equals(rye.getId())));
      assertEquals(rye.getId(), authority.reconcile(id, Map.of("name", "Rye", "language", "en")).getId());
      System.out.println("Live Caliper: " + catalogues.size() + " catalogue IDs; ICC Rye resolution, hierarchy, search and reconciliation passed");
    } finally { authority.releaseConfiguration(id); }
  }
}
