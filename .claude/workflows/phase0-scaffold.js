export const meta = {
  name: 'phase0-scaffold',
  description: 'Complete the Maven multi-module skeleton for nora-api: fill missing modules, then verify full build',
  phases: [
    { title: 'Fill', detail: 'one agent per missing module' },
    { title: 'Build', detail: 'mvn install until green' },
  ],
}

const MODULES = [
  { artifactId: 'nora-security', type: 'common' },
  { artifactId: 'agent-api', type: 'api' },
  { artifactId: 'gateway-service', type: 'service' },
  { artifactId: 'file-service', type: 'service' },
  { artifactId: 'rag-service', type: 'service' },
  { artifactId: 'automation-service', type: 'service' },
  { artifactId: 'datasource-service', type: 'service' },
]

const BOM = `BOM (all in parent dependencyManagement already — reference versions ONLY for understanding, do not redeclare):
  spring-cloud 2023.0.3, spring-cloud-alibaba 2023.0.1.2, langchain4j-bom 1.0.1, dubbo 3.3.0. Parent chain: org.springframework.boot:spring-boot-starter-parent:3.3.4.`

const ctx = `Project: Nora personal workbench backend, D:\\claude\\Nora\\nora-api (Windows Git Bash; use forward slashes in bash, backslashes only in Java code).
A previous workflow run already created MOST modules. Existing + verified good (do not recreate): root pom.xml (com.nora:nora-parent:0.1.0-SNAPSHOT, BOMs imported, services profile activeByDefault), .gitignore, docker-compose.yml, common/nora-common (ApiResponse/BusinessException/GlobalExceptionHandler + test), api/rag-api, api/file-api, api/datasource-api, api/env-api, api/automation-api (all with records + trivial JUnit tests), services/agent-service (AgentApplication + AgentController /api/chat/health + yml + test), services/env-service (same pattern, port 8085, ServiceController /api/environment/health).
READ existing siblings for exact patterns before writing — especially services/env-service/pom.xml and services/env-service/src/**, api/env-api/**, common/nora-common/pom.xml, and root pom.xml. Match their style: parent relativePath, dependency set, surefire 3.2.5 for api modules.
${BOM}
Existing pom excerpts for reference — nora-common deps: spring-boot-starter (compile) + spring-boot-starter-web (provided) + spring-boot-starter-test (test). api modules: junit-jupiter (test) only + surefire 3.2.5. services: spring-boot-starter-web + actuator + com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-discovery (no version, BOM-managed) + com.nora sibling modules at \${project.version} + spring-boot-starter-test; spring-boot-maven-plugin repackage goal if present in sibling poms.
agent-api jar is MISSING (directory does not exist yet).
`

phase('Fill')

const results = await parallel(MODULES.map((m) => () => agent(
  `${ctx}

YOUR TASK: create ONLY the ${m.artifactId} module. ${m.type === 'common' ? 'common/nora-security/ exists with pom.xml already — verify it, then ADD the missing Java sources listed below (src dirs exist but are empty).' : m.type === 'api' ? 'api/agent-api/ does not exist — create pom + sources from scratch.' : 'services/${m.artifactId}/ is ' + (['gateway-service', 'rag-service', 'datasource-service'].includes(m.artifactId) ? 'absent — create fully. (file/automation-service dirs exist with EMPTY src dirs and NO pom — create fully.)' : 'absent — create fully.')}

Module specs:

--- nora-security (common) ---
Java sources (package com.nora.security):
- JwtProperties: record or @ConfigurationProperties(prefix="nora.security") class holding String secret (default "nora-dev-secret-change-me-32bytes!!") and long expirationMs (default 86400000).
- JwtUtil: final class, ctor (String secret); methods String generate(String subject) using io.jsonwebtoken jjwt 0.12 API (Jwts.builder().subject(subject).expiration(Date).signWith(SecretKey derived via Keys.hmacShaKeyFor(secret bytes)).compact()); String parseSubject(String token) via Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject(); throws JwtException on bad token. Secret must be >= 32 chars — pad/validate.
- JwtAuthFilter extends OncePerRequestFilter: reads Authorization Bearer header, on valid token sets UsernamePasswordAuthenticationToken(subject, null, empty list) into SecurityContext; on missing/invalid just continues (does not throw).
- Test JwtUtilTest: roundtrip generate→parseSubject returns subject; tampered token throws JwtException. Plain JUnit, no Spring context.
Dependencies pom already has: web, security, jjwt-api + impl/jackson runtime, junit. Keep as-is; fix ONLY if broken (e.g. jjwt version missing → add 0.12.6 to parent or module).

--- agent-api (api) ---
groupId com.nora, parent nora-parent relativePath ../../pom.xml, plain jar, package com.nora.agent.api, NO dependencies except junit-jupiter (test) + surefire 3.2.5.
Records (String-typed enums where noted):
- Citation(String docName, String source, int chunkIndex, double score, String snippet)
- enum ChatStepType { THINK, TOOL }
- enum ChatStepStatus { PENDING, RUNNING, COMPLETED, FAILED }
- ChatStepEvent(String id, ChatStepType type, String title, String detail, Long durationMs, ChatStepStatus status)
- ModelInfo(String protocol, String modelName)
- interface AgentService (placeholder for future Dubbo; one method: List<ModelInfo> listModels())
Test: record field access + enum roundtrip.

--- gateway-service (service, port 8080) ---
Reactive (WebFlux-based). Dependencies: spring-cloud-starter-gateway + com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-discovery + actuator. NO spring-boot-starter-web.
package com.nora.gateway. GatewayApplication. application.yml:
  server.port 8080, spring.application.name gateway-service, management health exposed,
  spring.cloud.gateway.routes: [{id: agent, uri: lb://agent-service, predicates: [Path=/api/chat/**]}].
Main class needs @EnableDiscoveryClient? sibling services don't use it — plain @SpringBootApplication + spring-cloud-starter makes discovery auto. Match siblings.
Unit test: none possible without booting — write a plain unit test asserting a RouteLocator? Simpler: test that GatewayApplication class exists/annotated via reflection assert (Annotations findable). Keep test dependency spring-boot-starter-test.
NOTE: spring-cloud-starter-gateway needs spring-cloud-dependencies BOM — already in parent.

--- file-service (port 8081) ---
Pattern EXACTLY like services/env-service: package com.nora.file, FileApplication, FileController @RestController @RequestMapping("/api/files") GET /health returning ApiResponse.ok("file-service up") — wait, env-service controller returns what? READ it and mirror the return type exactly. Deps: web, actuator, nacos-discovery, com.nora:nora-common, com.nora:file-api, starter-test. Unit test instantiating controller directly, same as env-service test.

--- rag-service (port 8082) ---
Same pattern. package com.nora.rag, RagController @RequestMapping("/api/rag") GET /health. Deps: web, actuator, nacos-discovery, nora-common, rag-api, starter-test.

--- automation-service (port 8086) ---
Same pattern. package com.nora.automation, AutomationController @RequestMapping("/api/automations") GET /health. Deps: web, actuator, nacos-discovery, nora-common, automation-api, starter-test.

--- datasource-service (port 8084) ---
Same pattern. package com.nora.datasource, DatasourceController @RequestMapping("/api/datasources") GET /health. Deps: web, actuator, nacos-discovery, nora-common, datasource-api, starter-test.

GENERAL RULES:
- Match sibling file style precisely (imports, javadoc style, yml key order).
- No Dockerfiles. No @SpringBootTest. No nacos in tests.
- After writing, run a syntax self-check if possible: cd D:\\claude\\Nora\\nora-api && mvn -q -pl <module-path> -am -DskipTests compile 2>&1 | tail -5 — if parent/other deps fail to resolve (not your module's fault), note it and move on; if YOUR module has compile errors, fix them.
- Report (under 200 words): files created, compile check result, deviations.`,
  { label: `fill:${m.artifactId}`, phase: 'Fill', effort: 'medium' }
)))

const ok = results.filter(Boolean).length
log(`Filled ${ok}/${MODULES.length} modules`)

phase('Build')
const buildReport = await agent(`${ctx}

All modules should now exist. Your job: make the FULL build green.
1. cd D:\\claude\\Nora\\nora-api && mvn -B install 2>&1 | tail -60 — capture result.
2. Fix every failure: missing modules in parent <modules>, pom errors, compile errors, test failures. Edit surgically (Edit tool), never wholesale rewrite a module another agent wrote.
3. Iterate mvn -B install (or -pl <module> -am for targeted) until ALL modules build AND tests pass. Surefire must run tests (check "Tests run" lines).
4. Sanity: mvn -B -q install once more for reproducibility.
5. ALSO verify: gateway-service jar built contains GatewayApplication.class; agent-api jar installed to local repo.
Known risk areas: (a) spring-cloud-gateway vs boot version compatibility; (b) jjwt version not BOM-managed — if unresolved add explicit 0.12.6 to nora-security pom; (c) surefire default too old for JUnit5 in modules where it wasn't declared — parent pluginManagement should pin surefire 3.2.5 globally if any module silently skips tests; (d) empty src dirs left from crashed agents (automation/file-service) — ensure final files exist.
Never use -DskipTests in final verification run. Report: final command + outcome, per-module test counts, fixes applied, deviations. Under 400 words.`,
  { label: 'build-verify', phase: 'Build', effort: 'high' })

return { filled: ok, total: MODULES.length, build: buildReport }
