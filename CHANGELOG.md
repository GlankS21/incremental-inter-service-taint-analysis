# Changelog

## Unreleased

### Fixed
- **A repository without a `vkr-taint.yml` is checked with every catalogue.** The generated
  configuration left out `cwe-78` (OS command injection) and `cwe-22` (path traversal), so those
  findings were never reported without a configuration file. OWASP BenchmarkJava without a
  configuration file: 518 → 777 of 777 vulnerable tests found (cmdi 0 → 126, pathtraver 0 → 133),
  no false positive, the same result as with the suite's configuration.
- **Code scanning ranks command injection and path traversal as errors.** Their SARIF results were
  warnings with security-severity 5.0 (medium) and a rule without a name or description. They are
  now `CommandInjection` (9.8) and `PathTraversal` (7.5).
- **Upgrading DeltaTaint does not fail pull requests on the findings the upgrade adds.** A report
  records the version of the tool that made it (`tool`). Against a baseline of another version,
  which includes every baseline of 1.4 and older, new findings are still marked and listed, with a
  warning, but they do not fail the gate: they may come from the tool rather than from the change.
  A service that could not be analysed still fails it. The next push to the baseline branch stores a
  baseline of the new version; a `gate.baseline` file has to be regenerated.
  OWASP BenchmarkJava, unchanged code, against a baseline of an earlier version (1243 findings): the
  gate failed on 27 new findings (19 command injection, 8 path traversal), all of them from the tool.
  It now passes with a warning and lists the same 27. Against a baseline of the same version that
  lacks one finding, it still fails on that finding.
- **A field stored and read back within one service is not a service call.** Such chains were
  counted "across a channel", and their SARIF message read `across 1 service call(s):
  order-service -> order-service`. The run and the job summary now count chains across services
  and chains through the database apart. In SARIF, `crossService` is true only when the service
  changes, the new `throughDatabase` marks a DB hop, and the message names the stored field. The
  testbed's base commit: 16 chains, "6 across a channel" → "0 across services, 6 through the
  database"; train-ticket: 106 of 243 chains across services, as before.
- **A service that could not be analysed says why, and its log is kept.** The reason used to be
  "analysis failed", and the log stayed in a workdir that the action does not keep. The reason now
  tells out of memory (raise the heap) from a process killed for lack of memory (exit 137: lower
  it), a stack overflow, or the exit status, and the log is copied next to the report as
  `vkr-taint-logs/<service>.log`, which the action uploads in the `vkr-taint-report` artifact.
  Securibench Micro with a 256m heap: "analysis failed" and no log in the output → "out of memory:
  the Java heap (256m) is too small" and `vkr-taint-logs/securibench-micro.log`.
- **A sink that the configuration declares is reported, with the CWE it names.** The analysis
  found the flows into a sink of a catalogue file in `taint.sinks` or of the overrides' `sinks`, but
  the report dropped them unless the method name was a built-in catalogue's, and then gave them
  that catalogue's CWE: an XPath query (`evaluate`) was template injection. A sink, or its file, now
  names its weakness with `cwe: CWE-643`; a sink without one keeps the CWE of a catalogue of the same
  method name, else it is CWE-74 under the new `DeclaredSink` rule. A declared sink of the class the
  call goes to wins over a catalogue. A `cwe` that is not of the form `CWE-89` is a configuration
  error. OWASP BenchmarkJava, its configuration plus a file of LDAP and XPath sinks: LDAP injection
  0 → 27 of 27 vulnerable tests found (14 when the file declares only `DirContext.search`, not
  `InitialDirContext.search`), XPath injection 0 → 15 of 15, the 7 XPath tests reported as template
  injection now CWE-643; no false positive, the four scored categories unchanged (777 of 777).
  Without declared sinks the findings do not change.

### Changed
- One table, `analysis/taint/scripts/cwe.py`, describes every built-in catalogue: the name a
  configuration lists, how a sink call is recognised, and its SARIF rule. The configuration check,
  the generated configuration, the summaries and the SARIF writer read it, so they cannot disagree.

## 1.4.0

DeltaTaint 1.4.0 follows taint through the containers an application builds: a sink that receives a
list or map reports what it holds, and elements read through iterators and map entries keep their
taint.

### Improved
- **Sinks see container contents.** An application container passed to a sink (a `List` to
  `new ProcessBuilder(list)`, a map to a logger) reports the taints it holds.
- **Iterators and map entries.** `iterator().next()`, `entrySet()` with `getKey`/`getValue`, and
  iterated `values()`/`keySet()` return elements of the element type, so the taint survives a cast
  back to `String`. Map keys are kept apart from values: `keySet` sees the keys, `get` and `values`
  see the values.
- **URI components.** The component constructors of `java.net.URI` (scheme, host, path, query, ...)
  pass taint to the URI in the `cwe-918` and `cwe-22` catalogues.
- **String builders** are tracked as containers when the points-to analysis keeps them apart. With
  Tai-e's default (`merge-string-builders: true`) they are one object, which is not tracked, so
  this changes no result yet.

### Results
- OWASP BenchmarkJava: 777 of 777 vulnerable tests found (TPR 100 %, from 96.5 %), FPR 0.0 %;
  cmdi 107 → 126 and pathtraver 125 → 133.
- Securibench Micro: 115 of 133 statements (recall 86.5 %, from 83.5 %), false sinks unchanged at
  14 (precision 89.1 %).
- Juliet Java 1.3, 1332 servlet test cases: unchanged (1188 found, 33 of 2622 good flows reported).
- The regression gate passed against the 1.3.0 baseline, which is re-frozen at these numbers.
- Testbed: unchanged. All 6 seeded vulnerabilities are found and none of the 4 negatives is
  reported; the base commit gives 16 chains (6 across a channel).

### Upgrading
- `uses: GlankS21/incremental-inter-service-taint-analysis@v1` now gives this release. Pin
  `@v1.4.0` for a fixed version.
- The analysis changes, so every run key changes. The first run after upgrading is a full
  analysis, about 4–6 minutes per service on a hosted runner; later runs reuse the cache as before.

## 1.3.0

DeltaTaint 1.3.0 reads more of what a client sends to a servlet application, no longer loses the
findings of an application whose classes share no package, and guards every change to the
analysis with a regression gate on three public suites.

### Improved
- **Servlet sources.** The request path (`getRequestURI`, `getRequestURL`, `getPathInfo`,
  `getPathTranslated`), `getRemoteUser`, the body (`getReader`, `getInputStream`) and every
  client-set cookie attribute are sources. Values the server decides (`getScheme`, `getMethod`,
  `getContextPath`, `getInitParameter`, ...) are not.
- **Element taint.** A tainted `String[]` (from `getParameterValues` or a `getParameterMap` value)
  taints its elements, so the taint survives being stored in a container and cast back.
- **Servlet entry points.** A servlet that inherits `doGet`/`doPost` from an abstract base class of
  the application is analysed. Before, only handlers declared in the servlet class itself were.
- **Transfers.** `StringTokenizer` (a query string parsed by hand) and the request-body readers
  (`InputStreamReader`, `BufferedReader.readLine`) pass taint. `List.remove(int)` and the other
  reads that return an element (`removeFirst`, `pollLast`, `elementAt`, ...) return what the list
  holds; on a list whose positions are known, `remove(i)` returns only the element at `i`.

### Fixed
- An application whose classes share no package (for example `testcases.*` and `juliet.support.*`)
  lost every finding: its sink sites were taken as library code. Each top-level package is now an
  application package.

### Results
- Regression gate (`analysis/experiments/public/precision_gate.py`): a change may not lose a true
  positive or add a false positive in any category against `results/gate-baseline.json`.
- OWASP BenchmarkJava: TPR 96.5 %, FPR 0.0 % (+96.5, from +94.6).
- Securibench Micro: 111 of 133 statements (recall 83.5 %, from 77.4 %), precision 88.8 %.
- Juliet Java 1.3, 1332 servlet test cases: recall 89.2 %, 33 of 2622 good flows reported (1.3 %).
  Missed: CWE81 `sendError` (not a sink: containers escape it) and Java serialization.
  The fixes above were found on this suite, so its numbers are not an unseen-data result.
- Testbed: unchanged. All 6 seeded vulnerabilities are found and none of the 4 negatives is
  reported; the base commit gives 4 findings and 16 chains (6 across a channel), as in 1.2.0.

### Upgrading
- `uses: GlankS21/incremental-inter-service-taint-analysis@v1` now gives this release. Pin
  `@v1.3.0` for a fixed version.
- The analysis changes, so every run key changes. The first run after upgrading is a full
  analysis, about 4–6 minutes per service on a hosted runner; later runs reuse the cache as before.
- A Spring service can get new findings only where application code reads a tainted list element
  with `remove`, `poll...`, `peek...` or `elementAt` (the testbed's are unchanged). A servlet
  application may get more: new sources, inherited handlers and, for an application under several
  top-level packages, findings that 1.2.0 dropped.

## 1.2.0

DeltaTaint 1.2.0 keeps container elements apart, makes the analysis cache small, and reruns a
service only for the configuration it is given.

### Licence
- The repository is licensed under AGPL-3.0, the licence of Micans, which the tool patches and
  runs.

### Improved
- **Containers.** Map values are kept apart by key when the key is a string constant
  (`put("a", v)` then `get("b")` is not a flow). Elements of a list that a method builds and reads
  in straight-line code with constant indexes (`add`, `remove(0)`, `get(1)`) are kept apart by
  position. Any other map or list read still returns everything stored in the container.
- **Incremental reruns.** A service's run key covers the overrides that service is given (common
  entries, its own `services:` entry, exclusions, call-site helpers), not the whole overrides
  file. An entry for one service no longer reruns the others.
- **Action cache.** Only the analysis results (`work/cache`, about 10 KB compressed for four
  services) are cached, keyed by their content. A run that adds no result saves nothing. Before,
  every run saved the per-service workdirs (about 320 MB) under a new key until the repository's
  10 GB cache limit evicted them.

### Fixed
- The launcher compiles on JDK 17: its sources are read as UTF-8 on every JDK.

### Security
- Every third-party action is pinned to a commit SHA.
- Action inputs reach the scripts through environment variables, never through `${{ }}` in a
  script.
- The checkout is mounted read-only into the analysis container.
- The job summary escapes text taken from the analysed sources (endpoint paths, class and method
  names), so it cannot become markdown or HTML.
- The example workflow grants `security-events: write` and `actions: read` to the analysis job
  only.

### Results
- OWASP BenchmarkJava (sqli, xss, cmdi, pathtraver; TPR − FPR): **+94.6** (TPR 94.6 %, FPR 0.0 %),
  from +74.5 in 1.1.0. The container changes were designed from the false-positive classes seen on
  this benchmark, so this number is not an unseen-data result.
- Securibench Micro, run once with no change to the tool: 103 of 133 vulnerable statements found
  (recall 77.4 %), precision 88.0 %. Per statement:
  `analysis/experiments/public/results/securibench-micro-per-statement.csv`.
- Testbed: the incremental report equals a full analysis in all 21 scenarios. All 6 seeded
  vulnerabilities are found and none of the 4 negatives is reported.

### Upgrading
- `uses: GlankS21/incremental-inter-service-taint-analysis@v1` now gives this release. Pin
  `@v1.2.0` for a fixed version. The README examples used the repository's former name.
- The cache path and every run key change. The first run after upgrading is therefore a full
  analysis, about 4–6 minutes per service on a hosted runner. Earlier `vkr-taint-cache-*` entries
  are no longer read; delete them, or let them expire after 7 days unused.
- The baseline is stored by pushes to the repository's default branch, as before. When the default
  branch changes, the first push to the new one stores a baseline. Until then, pull requests list
  findings without comparing them.

## 1.1.0

DeltaTaint 1.1.0 adds servlet applications, two sink catalogues and fewer false positives from
constant branches.

### New
- **Servlet applications.** A war whose classes extend `HttpServlet` (javax or jakarta) is analysed
  as a service:
  - `doGet`/`doPost`/... are entry points;
  - the request getters (`getParameter`, `getHeader`, `getCookies`, ...) are sources;
  - libraries are read from `WEB-INF/lib`;
  - when the war does not package the Servlet API (scope `provided`), signature-only stubs are used.
- **New sink catalogues:**
  - `cwe-78` (OS command injection): `Runtime.exec` and `ProcessBuilder`;
  - `cwe-22` (path traversal): `File` and stream constructors, and `Paths.get`.
- **`cwe-89`** also covers plain JDBC statement text (`Statement.execute*`/`addBatch`,
  `Connection.prepare*`) and `JdbcTemplate.batchUpdate`.

### Improved
- **`cwe-117`:** the SLF4J log message is a sink, also when it is built by concatenation
  (`log.info("code " + code)`). Before, only the `{}` arguments were.
- **Constant branches:** code behind an `if`, ternary or `switch` whose condition is constant inside
  the method is dropped before the analysis, including `"ABC".charAt(1)` and integer casts. The
  points-to analysis is flow-insensitive, so such code used to be reported.

### Results
OWASP BenchmarkJava, scored on four categories (sqli, xss, cmdi, pathtraver) as TPR − FPR:

| | TPR | FPR | Score |
|---|---|---|---|
| servlet support alone | 94.6 % | 54.5 % | +40.1 |
| 1.1.0 | 94.6 % | 20.1 % | **+74.5** |

- The ecommerce testbed gives the same chains as before.
- The remaining false positives come from a list element taken at another index or a map value
  read under another key; these are not handled yet.

### Upgrading
- `@v1` pointed to this release. Pin `@v1.1.0` for a fixed version.
- The analysis cache key includes the tool version. The first run after upgrading is therefore a
  full analysis, about 4–6 minutes per service on a hosted runner. Later runs re-analyse only the
  services that changed.

Pull requests: #11 (log message sink), #12 (servlet applications, `cwe-78` and `cwe-22`), #15 and #16
(constant branches).

## 1.0.0

The first public release of DeltaTaint: incremental inter-service taint analysis of Java Spring
Cloud microservices in CI. (The release notes of 1.0.0 and 1.1.0 were deleted with their GitHub
releases; these two sections are rebuilt from them and from the code at the tagged commits.)

- **Chains across services.** A chain runs from an externally reachable endpoint to a sink, through
  other services over HTTP (OpenFeign, `RestTemplate`), RabbitMQ (with the transactional outbox and
  dead-letter routing) or a database table that services share.
- **Incremental.** Only the services whose analysis inputs changed are re-analysed. The cache is
  keyed by the hash of each service's classes, shared modules, library jars, generated
  configuration and tool version.
- **Reports.** The chains go to GitHub code scanning as SARIF and to the job page as a summary.
- **Gate.** Pull requests are compared with a baseline that pushes to the default branch store;
  with `gate: 'true'`, a new finding fails the job.
- **Sink catalogues:** SQL injection (`cwe-89`), log injection (`cwe-117`), XSS (`cwe-79`),
  server-side request forgery (`cwe-918`) and template injection (`cwe-1336`).
- **Projects:** Maven multi-module Spring Cloud systems; the Gradle output layout is also read.
- Analysis is done by Micans, built from its published source (AGPL-3.0, Zenodo
  10.5281/zenodo.15050505) inside the action's image. A chain is a candidate, not a proof: precision
  is that of a 1-call context-sensitive pointer analysis.
