# UN authority

A k.LAB authority for UN-endorsed statistical classifications published by
[Caliper](https://caliper.integratedmodelling.org/), using the
[Integrated Modelling SPARQL endpoint](https://caliper.integratedmodelling.org/caliper/sparql).
Provider URN: `klab.authority.un`. Requires Java 21 and the current `klab-services` authority API.

Each worldview binding exposes **one SKOS ConceptScheme**. Bind the provider repeatedly to
use different classifications, editions, roots or languages. Sub-authorities are independent
classification configurations, rather than search filters. No FAO-hosted endpoint is used for data.

```kwv
identity CropClassificationIdentity
    requires authority UN.ICC {
        urn: "klab.authority.un",
        catalog: "ICC"
    }
;

identity HistoricalCropClassificationIdentity
    requires authority UN.ICC10 {
        urn: "klab.authority.un",
        catalog: "ICC_V1_0"
    }
;
```

`ICC` selects the latest edition available **when the bridge is configured**. The bridge retains
that edition and its term snapshot until the worldview is reloaded. It does not change meanings
partway through a reasoning session. `ICC_V1_0` always selects that edition, and fails if it is
unavailable. Reloading a versionless binding selects the newest available edition. Use versioned
bindings for reproducible models; the upstream publisher may still correct an edition in place.

## Countries: M49 UNSD and `geography:Country`

In the `geography` ontology, where `Country` is already a declared **identity** category,
bind M49 to a general geographical identity and map its country boundary to `geography:Country`:

```kwv
identity GeographicalIdentity
    requires authority UN.M49 {
        urn: "klab.authority.un",
        catalog: "M49",
        language: "en",
        semanticBoundaries: {
            "M49_COUNTRY": "geography:Country"
        }
    }
;
```

`M49_COUNTRY` identifies M49 UNSD concepts whose notation matches `codeM49` on a record
explicitly typed `geopolitical:self_governing` in the same Caliper endpoint. Membership is
established from those published assertions, rather than inferred from a label or leaf position.
This boundary follows Caliper's self-governing category; it does not include every country-or-area
entry or assert a separate sovereignty policy. Unattested entries do not acquire the boundary.
`M49_COUNTRY` is a semantic boundary, not a separate `catalog` value.

Matched country identities inherit from the bridge anchor and `geography:Country`, with M49
ancestry deferred. Regions such as Europe remain ordinary M49 identities with their full hierarchy,
and never acquire `geography:Country` through this mapping. Search still covers the M49 scheme.
Do not map the broader `M49` boundary to `geography:Country`: it also contains regions.

For example, search for `Spain`, then select the canonical candidate:

```text
UN.M49:[https://unstats.un.org/classifications/geo/M49/724]
```

Names are reconciled explicitly, not used as identity codes. The current Caliper dataset contains
both `/724` and `/M49-UNSD#724` URIs for Spain, so `Spain` or notation `724` alone is ambiguous.
Select a search candidate, or reconcile with all of these fields:

```json
{
  "name": "Spain",
  "language": "en",
  "uri": "https://unstats.un.org/classifications/geo/M49/724"
}
```

Both published Spain identities receive `M49_COUNTRY`; they remain distinct canonical URIs.
`FULL_HIERARCHY` resolution can subsequently expand the selected country's external ancestry.

## Classification configurations

The catalogue bundled for discovery was captured from the configured endpoint on 2026-10-16:
18 schemes, exposed through 30 distinct latest/versioned identifiers. Configuration always reads
the live catalogue; the bundled metadata cannot satisfy an offline configuration.

| Latest identifier | Versioned identifiers currently available | Scope |
| --- | --- | --- |
| `CPC` | `CPC_V2_0`, `CPC_V2_1` | Central Product Classification, core |
| `CPC_AG` | `CPC_AG_V2_1` | CPC agriculture expansion |
| `CPC_FI` | `CPC_FI_V2_1` | CPC fisheries and aquaculture subset |
| `ICC` | `ICC_V1_0`, `ICC_V1_1` | Indicative Crop Classification |
| `ISIC` | `ISIC_V4` | Industrial activities |
| `COICOP` | `COICOP_V1999` | Individual consumption by purpose |
| `ICATUS` | `ICATUS_V2016` | Activities for time-use statistics |
| `FCL` | `FCL_V1_0` | FAOSTAT Commodity List |
| `FPCD` | `FPCD_V2022` | Forest products |
| `WCACROPS` | `WCACROPS_V2020` | WCA crop list |
| `M49` | — | UNSD geographical codes, published as current |
| `M49_FAO` | — | FAO M49 scheme |
| `NOCS` | `NOCS_V2021_7` | FAO members |
| `NOCS_NONFAO` | `NOCS_NONFAO_V2021_7` | Non-FAO members |
| `NOCS_TERRITORIES` | — | Territories |
| `NOCS_ASSOCIATE` | — | Associate members |

CPC variants remain separate even when they share concept URIs. NOCS schemes remain separate even
when their published notations are identical. Numeric version comparison handles `1.10 > 1.9`.
The catalogue recognizes versioned classification URI paths, named current schemes and numeric
`owl:versionInfo`; ambiguous identifiers or latest editions fail explicitly. New standard URI-path
editions are discovered automatically during configuration. Newly introduced URI conventions may
require a catalogue mapping update. `UnAuthority.discoverClassifications()` refreshes the public
catalogue without configuring a bridge.

## Parameters

| Parameter | Default | Meaning |
| --- | --- | --- |
| `urn` | required | `klab.authority.un` |
| `catalog` | required unless supplied by a sub-authority view | One latest or versioned identifier above |
| `schemeUri` | unset | Exact live scheme URI, as an alternative to `catalog` |
| `language` | `en` | Preferred display language; English, untagged, then deterministic fallback |
| `searchLimit` | `25` | Maximum returned candidates, 1–100 |
| `timeoutSeconds` | `45` | Deadline per discovery/snapshot request, 1–180 seconds |
| `endpoint` | Integrated Modelling endpoint | Explicit HTTPS deployment override; loopback HTTP for tests |
| `semanticBoundaries` | empty | Optional classification IDs or `M49_COUNTRY` mapped to worldview identity URNs |

Unknown parameters fail validation. Root worldview concepts and local names belong to each bridge.
Configuration IDs are opaque and independent; releasing one bridge discards its snapshot without
changing other bridges. Java callers may use `subAuthority("ICC")` and omit `catalog` when configuring
that view. Undeclared dotted names are not inferred by the Reasoner: declare each worldview binding.

## Identity and hierarchy

### Independently owned configured snapshots

The provider implements the optional `SnapshottingAuthority` capture contract.
`captureConfiguration(id)` exports the selected scheme/edition, language, search
limit and bounded SKOS vocabulary (including parent edges and attested country
membership) as a deterministic `un-caliper-skos-v1` snapshot. RDF row ordering does
not change its bytes. `restoreConfiguration(request, snapshot)` validates the exact
request and format, reconstructs an independent bridge and validates the frozen
hierarchy without contacting Caliper. Identity, full-hierarchy, search and
reconciliation operations on that bridge use the same frozen vocabulary.

Snapshot state is limited to 32 MiB by the shared API and 200,000 vocabulary rows
by this provider. The normal `configure` route still discovers and loads live
data. Its zero response-cache lifetimes remain zero: an explicitly owned configured
snapshot is not permission to retain mutable results in the persistent response
cache. Reloading/capturing a new configuration is the boundary for selecting new
upstream content. `close()` releases all owned bridge state and HTTP clients.

Use matching Services/API and component builds for this optional contract. Candidate
compilation restores the proved state offline; accepted lookups keep that state,
rather than silently configuring a newer edition on their first request.

### Canonical identities and hierarchy

- The canonical ID is the published RDF concept URI. Unique `skos:notation` strings are accepted as
  lookup aliases; ambiguous codes fail and require explicit URI selection. Labels are never implicit
  code lookups. URI suffixes are not assumed to be codes: ICC `1.06` has URI suffix `1-06`.
- Search candidates return canonical IDs that resolve in the same configuration. For example,
  `UN.ICC:[https://stats.fao.org/classifications/ICC/v1.1/1-06]` denotes Rye. Locators use the core
  authority syntax encoder. Concept names hash the scheme URI and concept URI, avoiding lossy
  punctuation replacement and keeping editions/scopes distinct.
- `skos:broader` and inverse `skos:narrower` supply direct superclass edges. All parents are retained.
  Only roots inherit directly from the configured worldview identity. Reachable ancestors outside
  the selected scheme are loaded as hierarchy support: they resolve by canonical URI but are absent
  from search, notation aliases, reconciliation and selected-classification boundaries. This is
  needed by CPC fisheries and FAO M49. Cycles and ancestors absent from Caliper produce errors
  before materialization; no ancestry is silently cut off.
- `exactMatch`, `closeMatch`, `related`, XKOS correspondences and classification levels do not become
  identity equivalences or superclass edges. Shared labels and cross-edition mappings do not merge
  identities. This follows the uniform [Caliper SKOS model](https://www.fao.org/statistics/caliper/resources/en).
- Classification membership establishes semantic boundaries for the family and selected edition.
  Mapping either boundary opts into deferred ancestry. Explicit `FULL_HIERARCHY` resolution validates
  and supplies the complete ancestry; deferred records suppress all external parent/base edges.

Search matches codes, canonical URIs and SKOS preferred/alternative/hidden labels, with deterministic
ordering and provider relevance scores. Reconciliation accepts `name`, `notation`, `uri`, and optional
`language`, combining supplied constraints. Names must match an exact preferred or alternative label
(case-insensitive). Only one matching concept succeeds. This initial implementation performs SKOS
reconciliation locally; it does not depend on an undocumented Caliper reconciliation HTTP API.

Descriptions include available definitions, notes, scheme provenance and codes. Dedicated documentation
media URLs and worldview codelist seed exports are not implemented; their capabilities are not advertised.

## Runtime and compatibility

The provider loads the selected classification's relevant SKOS statements and reachable ancestors
into a bridge-local snapshot. It materializes identities on demand. Requests are read-only SPARQL SELECTs sent as UTF-8
`application/sparql-query` POSTs and read as SPARQL Results JSON. No RDF URIs are dereferenced.
Responses are bounded to 32 MiB, catalogue discovery to 2000 rows, and classification data to 200000
rows; overflow fails rather than returning partial results. Hierarchy validation is iterative and
bounded to 512 active ancestors and 20000 completed ancestors. HTTP errors, malformed results and
missing classifications fail explicitly.

The JDK HTTP client and the host-managed Jackson API avoid introducing Jena/RDF4J dependencies.
`klab.core.services` and `jackson-databind` are `provided`; Jackson's version is inherited from
`klab-services` (currently 2.19.0). Packaging targets the actual Reasoner and Resources server artifacts.
Persistent Reasoner identity/search/reconciliation caches are disabled because latest aliases and
current schemes can change across reloads; the per-bridge snapshot already avoids repeat requests.
Future persistent caching should include the selected scheme and a content revision in its key.

```shell
mvn test
mvn test -Dcaliper.live=true
mvn package
```

Ordinary tests use a loopback SPARQL fixture and require no external service. The opt-in live suite
checks ICC resolution, hierarchy, search and reconciliation, and audits all hosted scheme hierarchies.
The packaging goal attaches a KAR component artifact; the current plugin writes its archive as
`target/klab.authority.un-component-1.0.0-SNAPSHOT.jar`.
Builds tolerate the initial directory having no Git metadata. Service installation, worldview reload
and a real PF4J ingestion round trip remain deployment checks.

Contract references: [Authorities](../klab-services/docs/AUTHORITIES.md),
[authority codelists](../klab-services/docs/AUTHORITY_CODELISTS.md),
[components](../klab-services/docs/COMPONENTS.md),
[Taxa](../klab.authority.taxa/README.md), [PubChem](../klab.authority.pubchem/README.md).

See [implementation and live validation](docs/IMPLEMENTATION.md) for results, scope counts and
remaining deployment work.
