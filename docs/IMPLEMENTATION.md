# Implementation and validation

Validated on 2026-10-16 against `https://caliper.integratedmodelling.org/caliper/sparql`.

The implementation follows the current `klab-services` `api.services.Authority` contract,
`docs/AUTHORITIES.md`, `docs/AUTHORITY_CODELISTS.md` and `docs/COMPONENTS.md`, with the
Taxa and PubChem providers as component and bridge-lifecycle references. The old commented-out
Caliper provider in the Reasoner was inspected but is not used as the operational contract.

Implemented:

1. Replace archetype examples with an annotated UN provider and retain the PF4J entry point.
2. Discover SKOS classification schemes, separate variants, expose versioned IDs and latest aliases.
3. Configure independent single-scheme bridges with canonical URI identities and exact notation aliases.
4. Snapshot selected terms and ancestor support; validate hierarchy and support deferred ancestry.
5. Provide search, conservative exact-label reconciliation, provenance descriptions and diagnostics.
6. Package using host-provided APIs and Jackson, with no additional RDF stack.

`mvn -o package -Dcaliper.live=true` passed: 10 loopback regression tests and 2 opt-in live tests.
Live ICC checks covered code `1.06` → the published Rye URI, label, ancestry, search and reconciliation.
The live audit loaded every hosted scheme and validated every loaded concept's ancestry: no cycles
or missing ancestors were found. This is a provider/data check, not an installation test.

| Edition/scope | Loaded concepts, including hierarchy support |
| --- | ---: |
| COICOP 1999 | 229 |
| CPC 2.1 core | 4596 |
| CPC 2.1 agriculture | 1192 |
| CPC 2.1 fisheries | 162 |
| CPC 2.0 | 4409 |
| FCL 1.0 | 799 |
| FPCD 2022 | 771 |
| ICATUS 2016 | 231 |
| ICC 1.1 | 196 |
| ICC 1.0 | 162 |
| ISIC 4 | 766 |
| M49 UNSD | 559 |
| M49 FAO | 4 |
| NOCS members | 305 |
| NOCS associate members | 2 |
| NOCS non-members | 3 |
| NOCS territories | 108 |
| WCA crops 2020 | 326 |

CPC fisheries has 160 direct members plus two core ancestors. FAO M49 has one direct member plus
three UNSD ancestors. Tests ensure these supporting terms are absent from search, notation lookup
and reconciliation, and never acquire the selected scheme's semantic boundary merely by ancestry.
Counts refer to typed SKOS concepts, not every subject carrying `inScheme`.

The packaging plugin resolved 487 host-provided runtime coordinates from the Reasoner and Resources
servers and included zero dependency artifacts. The component archive contains the UN provider,
helpers, catalogue bootstrap and component metadata. Compilation and packaging establish compatibility
with the resolved host APIs; real service class-loader ingestion has not been exercised.

The country-boundary follow-up adds `M49_COUNTRY`, attested through Caliper's geopolitical
`self_governing` records and their `codeM49`, and documents its mapping to `geography:Country`.
`mvn -o test -Dcaliper.live=true` passed with 11 loopback tests and 3 live tests. The added checks
cover deferred country ancestry, ordinary region ancestry, unattested leaves, explicit expansion,
and live Spain/Europe membership. The whole `M49` boundary remains geographical, not country-only.

Next deployment work:

1. Ingest the component in a Reasoner and Resources service, reload a small worldview with separate
   latest and pinned classification bindings, and exercise the authenticated authority routes.
2. Add dedicated documentation media and authoritative codelist exports if consumers need them;
   descriptions and authority resolution already work without those optional APIs.
3. If persistent/offline reuse is needed, extend the cache contract to fingerprint the actual selected
   edition and snapshot content before enabling result retention for versionless bindings.
4. Extend catalogue naming rules for new nonstandard scheme URI conventions and add reconciliation
   policies only where the published vocabulary gives an unambiguous interpretation.
