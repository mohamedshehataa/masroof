---
name: dependency-verification
description: How version/CVE facts were verified for Masroof designs (Spring generations API, Maven Central BOM, OSV batch) and the jqwik removal reason
metadata:
  type: project
---
Verify versions live, never from memory. On 2026-10-24 these worked from Git Bash: `api.spring.io/projects/spring-boot/{generations,releases}` with jq, the BOM pom on repo1.maven.org, `api.osv.dev/v1/querybatch` (sanity-check it with a known-vulnerable version), and the Docker Hub `v2/repositories/library/<img>/tags?name=` endpoint for digests. Bare `python` is a WindowsApps stub, so use `source scripts/env.sh` first.

jqwik was removed in ADR-0004 (STORY-143). 1.10+ carries an anti-AI-agent usage clause and a stdout banner, and it is built on JUnit Platform 1.x. The owner picks the property-testing tool in the STORY-004 ADR.

**Why:** the S9/PROMPT stack text is older than the current releases, and every override needed proof.
**How to apply:** in any dependency or stack decision, re-query these sources and quote the dates. Do not propose jqwik again without the owner's decision. See [[story-size-cap]].
