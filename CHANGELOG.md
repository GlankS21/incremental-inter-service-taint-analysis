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

## 1.1.0 and 1.0.0

See the [release notes](https://github.com/GlankS21/incremental-inter-service-taint-analysis/releases).
