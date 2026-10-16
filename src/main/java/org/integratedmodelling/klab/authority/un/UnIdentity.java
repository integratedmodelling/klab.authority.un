package org.integratedmodelling.klab.authority.un;

import java.net.URL;
import java.util.*;
import org.integratedmodelling.klab.api.services.Authority;
import org.integratedmodelling.klab.api.services.runtime.Notification;

/** Canonical RDF identity, scoped to the selected classification and worldview bridge. */
record UnIdentity(
    String id,
    String conceptName,
    String authorityName,
    String baseIdentity,
    List<String> parentIds,
    String description,
    String label,
    float score,
    String locator,
    List<Notification> notifications,
    Set<String> semanticBoundaries)
    implements Authority.Identity {
  UnIdentity {
    parentIds = List.copyOf(parentIds);
    notifications = List.copyOf(notifications);
    semanticBoundaries = Set.copyOf(semanticBoundaries);
  }

  @Override
  public String getId() {
    return id;
  }

  @Override
  public String getConceptName() {
    return conceptName;
  }

  @Override
  public String getAuthorityName() {
    return authorityName;
  }

  @Override
  public String getBaseIdentity() {
    return baseIdentity;
  }

  @Override
  public List<String> getParentIds() {
    return parentIds;
  }

  @Override
  public List<String> getParentRelationship() {
    return List.of();
  }

  @Override
  public Map<String, URL> getDocumentation() {
    return Map.of();
  }

  @Override
  public String getDescription() {
    return description;
  }

  @Override
  public String getLabel() {
    return label;
  }

  @Override
  public float getScore() {
    return score;
  }

  @Override
  public String getLocator() {
    return locator;
  }

  @Override
  public List<Notification> getNotifications() {
    return notifications;
  }

  @Override
  public Set<String> getSemanticBoundaries() {
    return semanticBoundaries;
  }
}
