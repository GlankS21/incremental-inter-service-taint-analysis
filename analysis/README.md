# vkr-taint

Incremental inter-service taint analysis for Java (Spring Cloud) microservice systems in a CI/CD
pipeline. It finds chains from an externally reachable endpoint to a sink, also when the data passes
through other services (HTTP calls, AMQP messages), and on each commit it re-analyses only the
services whose analysis inputs changed.

```
ep:ts-preserve-other-service:POST /api/v1/preserveotherservice/preserveOther <0/accountId>
  --HTTP /api/v1/securityservice/securityConfigs/{accountId}-->  ep:ts-security-service:GET ... <0>
  --HTTP /api/v1/orderservice/order/security/{checkDate}/{accountId}-->  ep:ts-order-service:GET ... <1>
  CWE-117 @ OrderController#securityInfoCheck:118
```

How it works:

1. **Channel model.** `channel-extractor` reads the sources and lists the endpoints, Feign and
   `RestTemplate` clients, RabbitMQ publishers, bindings and listeners, and the channels between
   them. It takes seconds.
2. **Per-service analysis.** Each service is analysed alone by [Micans](#micans) (pointer and taint
   analysis on Tai-e) with a taint configuration generated from its bytecode and the channel model:
   sources are endpoint parameters and listener payloads, sinks are the selected catalogues, and
   channel calls are sinks of their own.
3. **Cache.** A service's run is keyed by the hash of everything it reads: its classes, the classes
   of shared modules, its library jars (CRC per entry), the generated configuration, the tool
   scripts, the Micans jar and the JDK. On a commit, a service whose key is unchanged is not run
   again, and a rebuilt jar with the same code is a hit.
4. **Composition.** The per-service summaries are joined along the channel model into chains to a
   sink. This is cheap and runs every time. The chains of one entry point and one sink method form a
   finding: the same flow is printed once per granularity of the source (`<0>`, `<0/warehouse>`,
   `<AddCartItemRequest.warehouse>`) and once per path through the channels. Findings are compared
   with a baseline report without line numbers, so moved code is not a new finding.

On the history of two public systems, the incremental result equals a cold full analysis at the
last commit: mall-swarm, 12 commits, 21 = 21 chains, 2.3× less analysis time; train-ticket,
29 commits and 37 services, 243 = 243 chains, 3.95×. The data is in
[`experiments/public/results`](experiments/public/results).

## Quick start

### GitHub Action

Build the project first; the action does not build it. Then add:

```yaml
permissions:
  contents: read
  security-events: write          # SARIF upload to code scanning
jobs:
  taint:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21', cache: maven }
      - run: ./mvnw -B -DskipTests package
      - uses: GlankS21/incremental-inter-service-taint-analysis@v1
        with:
          path: .                 # the Maven multi-module root
```

The action:

- builds the tool image, with its layers kept in the Actions cache;
- restores the analysis cache of the latest run;
- on a pull request, compares the result with the baseline stored by the last push to the default
  branch;
- uploads `vkr-taint.sarif` to code scanning;
- fails with `gate: 'true'` (the default) on a new finding or on a service that could not
  be analysed. Against a baseline made by another version of DeltaTaint, new findings do not fail:
  they may come from the tool rather than from the change. The job shows a warning, and the next
  push to the default branch stores a baseline of the new version.

[`.github/workflows/taint.yml`](../.github/workflows/taint.yml) runs it on the testbed of this
repository. The first run of a system is a full analysis, which takes minutes per service.

| Input | Default | |
|---|---|---|
| `path` | `.` | project root, relative to the workspace |
| `config` | `<path>/vkr-taint.yml`, else generated | configuration file |
| `services` | all | comma-separated subset |
| `gate` | `true` | fail on new findings or a failed service |
| `heap` | `12g` | Micans heap (a hosted runner has 16 GB) |
| `upload-sarif` | `true` | needs `security-events: write`; set `false` for pull requests from forks |
| `baseline-branch` | default branch | pushes to it store the baseline |

### Docker

```sh
docker build -t vkr-taint analysis
docker run --rm -v "$PWD:/repo" -v vkr-cache:/cache vkr-taint /repo --build none --out /repo/vkr-taint-out
```

The image builds Micans from its published source; see [Micans](#micans). Keep the `/cache` volume
between runs: it is the incremental cache.

### Local

Requirements:

- JDK 21 or newer as `JAVA_HOME` (Micans runs on it);
- `VKR_MICANS_JAR` pointing at a Micans jar built with
  [`taint/patches/build_micans.sh`](taint/patches/build_micans.sh) (that script needs JDK 17);
- `python3` 3.9 or newer, with no packages;
- `sh` and `unzip`;
- the channel-extractor jar: `cd channel-extractor && ./mvnw -DskipTests package`.

```sh
analysis/vkr-taint <repo> [--build auto|mvn|docker|none] [--baseline old-report.json] [--gate] [--out DIR]
```

| `--build` | |
|---|---|
| `auto` (default) | runs Maven when a service has no built artifacts |
| `mvn` | `./mvnw` or `mvn -B -DskipTests package` on this machine (trusted code) |
| `docker` | builds in a Maven container (`--image`), copying the sources in and the build out, so the repository's build plugins never run on this machine (untrusted code) |
| `none` | uses what is built |

It runs on Linux and on Windows (Git Bash).

## Configuration: `vkr-taint.yml`

Without a file in the repository root, everything is detected. A file overrides any part of the
detection. `${NAME}` and `${NAME:-default}` are taken from the environment; relative paths are
relative to the file.

```yaml
version: 1
project:
  root: .                       # Maven multi-module root (Spring Boot services, or a servlet war)
  gateway-config: auto          # Spring Cloud Gateway routes: which endpoints are exposed
services: auto                  # or [order-service, ...] or {name, module, jar, classes}
shared: auto                    # modules packaged into services (common-lib), analysed as application code
taint:
  sinks: [cwe-89, cwe-117]      # built-in catalogues, or Tai-e taint yml files
  sources: auto                 # endpoints exposed through the gateway + listeners
  discovery: annotations        # request bodies, @Entity classes, channel payloads
  overrides: overrides.yml      # optional: extra sources, sinks, sanitizers, exclusions
analysis:
  context: 1-call
  heap: 24g
  world-cache: off              # off | rw | ro: reuse Micans' serialized World (large files)
toolchain:
  java-home: ${JAVA_HOME}
  micans-jar: ${VKR_MICANS_JAR}
gate:
  baseline: reports/taint-baseline.json
  fail-on: new-chains           # or none
```

Sink catalogues:

- **`cwe-89`**: JPA `EntityManager.createQuery`/`createNativeQuery` (javax and jakarta),
  `JdbcTemplate` `query*`/`update`/`execute`/`batchUpdate`, plain JDBC statement text
  (`Statement.execute*`/`addBatch`, `Connection.prepare*`), and MyBatis mapper parameters used with `${}`.
- **`cwe-117`**: the message of SLF4J `Logger` calls (also when it is built by concatenation, e.g.
  `log.info("code " + code)`) and their `{}` arguments. Log4j and JUL are not in the catalogue.
- **`cwe-79`**: the text body of a request handler: the `String` a `@ResponseBody`/`@RestController`
  method returns, or the `String` passed to `ResponseEntity.ok`/`body`. It is checked by the plugin
  `vkr.plugin.ResponseBodySink`, because Spring writes the body outside the analysed code. Object
  bodies are serialised as JSON and are not sinks. Spring `HtmlUtils.htmlEscape`, commons-text and
  lang3 `escapeHtml4`, commons-lang `escapeHtml`, and OWASP `Encode.forHtml` are sanitizers. In a
  servlet application the body is what the servlet writes to its `PrintWriter` (`print`, `println`,
  `write`, `format`, `printf`, `append`).
- **`cwe-918`**: `URL.openConnection`/`openStream` and `HttpRequest.newBuilder(URI)`/`Builder.uri`.
  `URI.create`, `new URL(..)` and the component constructors of `URI` (scheme, host, path, query,
  ...) carry taint to the URL.
- **`cwe-1336`**: a template compiled from a string: the FreeMarker `Template` constructors,
  Thymeleaf `TemplateEngine.process` and Velocity `evaluate`.
- **`cwe-78`**: an OS command: `Runtime.exec` (command line, arguments and environment) and
  `ProcessBuilder` (constructor and `command`).
- **`cwe-22`**: a file named by request text: the `File`, `FileInputStream`, `FileOutputStream`,
  `FileReader`, `FileWriter` and `RandomAccessFile` constructors and `Paths.get`.

Without a `vkr-taint.yml`, every catalogue is enabled. A file that leaves out `taint.sinks` gets
`[cwe-89, cwe-117]`; list the other catalogues there to enable them. Each catalogue is a SARIF rule
with its own level and `security-severity` (`cwe-117` a warning, the others errors; the names and
values are in [`taint/scripts/cwe.py`](taint/scripts/cwe.py)).

Examples: [`taint/config/ecommerce.vkr-taint.yml`](taint/config/ecommerce.vkr-taint.yml) (the
testbed); [`experiments/public/*.vkr-taint.yml`](experiments/public) (train-ticket, mall-swarm,
OWASP BenchmarkJava, Securibench Micro).

## Output

| File | Content |
|---|---|
| `vkr-taint-report.json` | see the fields below |
| `vkr-taint.sarif` | SARIF 2.1.0, see below |

Fields of `vkr-taint-report.json`:

- `chains`: every chain to a sink;
- `findings`: the chains grouped by entry point and sink method (`key`, `chains`);
- `tool`: the version of the tool that made the report, a hash of what the findings depend on (the
  scripts, the launcher plugins and stubs, Micans, the channel extractor and the JDK);
- `baselineTool`, `toolChanged`: the `tool` of the baseline (none in reports of 1.4 and older) and
  whether it differs from this run's. If it differs, new findings do not fail the gate, so a
  `gate.baseline` file has to be regenerated after an upgrade;
- `newFindings`, `removedFindings`: finding keys compared with `--baseline`, which the gate uses;
- `new`, `removed`: chains compared with `--baseline`;
- `unchecked`: baseline chains through a service that failed now;
- `failed`: services that could not be analysed;
- `services`: per service, the run key and whether the run and summary came from the cache, and
  the seconds.

In `vkr-taint.sarif`:

- each finding is one result, located at the sink, with its source labels in the message;
- `codeFlows`, one per chain of the finding, go through each endpoint's handler;
- `partialFingerprints` do not change when only line numbers change;
- `baselineState` is `new` or `unchanged`;
- rules carry `security-severity` for code scanning.

| Exit code | |
|---|---|
| 0 | done (and with `--gate`: no new finding, every service analysed) |
| 1 | `--gate`: new findings against a baseline of the same version of the tool |
| 2 | `--gate`: a service could not be analysed, so its chains are missing |
| 3 | configuration or build error |

A service whose analysis fails does not stop the run. It is listed under `failed`, and the log path
is in the report.

### Servlet applications

A module packaged as a war whose classes extend `HttpServlet` (javax or jakarta), without Spring
request mappings, is a service as well. Its `doGet`/`doPost`/... methods are the entry points
(`vkr.plugin.ServletEntryPoints`), also when a servlet inherits them from an abstract base class of
the application. The sources are what the client controls:
- the parameters, headers and query string (`getParameter`, `getParameterValues`, `getParameterMap`,
  `getParameterNames`, `getHeader`, `getHeaders`, `getHeaderNames`, `getQueryString`);
- the path (`getRequestURI`, `getRequestURL`, `getPathInfo`, `getPathTranslated`) and `getRemoteUser`;
- the body (`getReader`, `getInputStream`);
- the cookies (`Cookie.getValue`, `getName`, `getComment`, `getDomain`, `getPath`).

Values the server or the container decides (`getScheme`, `getProtocol`, `getMethod`, `getAuthType`,
`getServletPath`, `getContextPath`, `getInitParameter`) are not sources. A taint of a `String[]` or
`Map` value is also a taint of its elements. A finding names `Servlet#doPost`. The libraries are read
from `WEB-INF/lib`. The Servlet API is usually not packaged (scope `provided`): the analysis then uses
the signature-only stubs in [`taint/servlet-stubs`](taint/servlet-stubs). Servlet code handles raw
request strings, so a servlet application also gets JDK transfers for splitting, tokenizing,
replacing, encoding and decoding text and for reading the body, which the Spring services do not
need.

## Limits

- **Maven projects only.** The Gradle layout (`build/classes`, `build/libs`) is read, but
  `--build` runs Maven.
- **Channels:**
  - covered: Spring MVC endpoints, OpenFeign, `RestTemplate`, RabbitMQ (including transactional
    outbox and dead-letter routing), and tables shared by services (entity fields);
  - not covered: gRPC, Kafka, `WebClient`, GraphQL.
- **Kotlin:** the channel model is built from Java sources. Request handlers of Kotlin controllers
  are read from the bytecode and become sources when the system has no gateway, but they have no
  endpoint id (a finding names `Class#method`), and Kotlin Feign clients and listeners are not seen.
- **Servlets:** one war is one service; servlet filters, JSPs and `web.xml` mappings are not read, and
  `HttpSession` attributes are not followed.
- **Sinks:** only the catalogues above are built in. Others can be given as Tai-e taint yml files.
- **Cost:**
  - a commit that changes a shared module or a library version re-analyses every service that
    packages it, and that run is about as long as a full one;
  - Micans' heap need grows with the service; the measurements used 24 GB, and a service of
    mall-swarm ran with 12 GB.
- **Precision:** inherits Micans (1-call context sensitivity).
  - A chain is a candidate, not a proof.
  - The points-to analysis is flow-insensitive. Code behind an `if` or `switch` whose condition
    is constant inside the method (`int num = 86; if ((7 * 42) - num > 200)`, `"ABC".charAt(1)`)
    is dropped before the analysis (`vkr.plugin.ConstantBranches`).
  - Containers (`vkr.plugin.ContainerTaint`, `LocalLists`):
    - map values are kept apart by key when the key is a string constant (`put("a", v)` then
      `get("b")` is not a flow);
    - map keys are kept apart from values: `keySet` sees the keys, `values` and `get` see the values,
      `entrySet` sees both;
    - the elements of a list that a method builds and reads in straight-line code with constant
      indexes (`add`, `remove(0)`, `get(1)`) are kept apart by position;
    - any other map or list read returns everything stored in the container;
    - `iterator().next()`, `entrySet()` with `getKey`/`getValue`, and `values()`/`keySet()` iterated
      return elements of the element type, so a cast back to `String` keeps the taint;
    - a sink argument that is a container of the application (a `List` passed to
      `new ProcessBuilder(list)`, a map passed to a logger) reports what the container holds;
    - a `StringBuilder` or `StringBuffer` is a container of what is appended to it only when the
      points-to analysis keeps builders apart. By default (Tai-e `merge-string-builders: true`) all
      builders are one object, which is not tracked: an alias of a builder that a tainted string is
      appended to (Securibench `Aliasing5`) is missed. Keeping them apart found that case and cost
      19 % more points-to time on Securibench Micro.
  - On the testbed's seeded set, the sanitised negatives are not reported.
  - On public systems, false positives were found and fixed per case.
  - Public suites, scored per test case and checked by a regression gate
    ([`precision_gate.py`](experiments/public/precision_gate.py)): a change to the analysis may not
    lose a true positive or add a false positive in any category of any suite against
    [`results/gate-baseline.json`](experiments/public/results/gate-baseline.json).
    - OWASP BenchmarkJava (sqli, xss, cmdi, pathtraver)
      ([`results/benchmarkjava-per-test.csv`](experiments/public/results/benchmarkjava-per-test.csv)):
      777 of 777 vulnerable tests found (TPR 100 %), 0 of 701 safe tests reported (FPR 0.0 %).
    - Securibench Micro ([`securibench-micro.vkr-taint.yml`](experiments/public/securibench-micro.vkr-taint.yml),
      [`securibench_score.py`](experiments/public/securibench_score.py),
      [`results/securibench-micro-per-statement.csv`](experiments/public/results/securibench-micro-per-statement.csv)):
      115 of 133 `/* BAD */` statements found (recall 86.5 %), 14 false sinks (precision 89.1 %).
      Misses include reflection (4), session attributes (3), `getInitParameter` and request
      metadata such as `getProtocol` (not sources), a list stored in another list (`Collections3`)
      and an alias of a merged `StringBuffer` (`Aliasing5`). False
      positives: array elements are not kept apart by index, and a field write does not overwrite
      the earlier one.
    - Juliet Java 1.3, the 1332 servlet test cases of CWE 23, 36, 78, 80, 81, 83 and 89
      ([`build_juliet.py`](experiments/public/build_juliet.py),
      [`juliet_score.py`](experiments/public/juliet_score.py),
      [`results/juliet-per-flow.csv`](experiments/public/results/juliet-per-flow.csv)): 1188 found
      (recall 89.2 %), 33 of 2622 good flows reported (1.3 %). Misses: CWE81 (111), whose sink
      `sendError` is in no catalogue because Tomcat and Jetty escape the message, and data passed
      through Java serialization (33). The false positives are flow variant 68: the bad and the
      good flow share one static field, and the analysis does not order its write and read.
      Three generic gaps were found on this suite and fixed (entry points inherited from a base
      class, `StringTokenizer`, `List.remove(int)`), so these numbers are not an unseen-data result.
## Micans

Micans (Zhang et al., *Bridge the Islands: Pointer Analysis for Microservice Systems*, artifact
[10.5281/zenodo.15050505](https://doi.org/10.5281/zenodo.15050505)) is licensed under AGPL-3.0.
This repository does not contain its source or jar.

[`taint/patches/build_micans.sh`](taint/patches/build_micans.sh):

1. downloads `micans-source.tar.xz` from Zenodo, or takes a local copy;
2. checks its sha256;
3. applies the patches of [`taint/patches`](taint/patches);
4. builds `micans.jar`.

The patches:

| Patch | What it does |
|---|---|
| `micans-world-cache` | content-hash world cache |
| `micans-phantom-beans` | phantom bean classes |
| `micans-static-base-transfer` | static calls in accessor transfers |
| `micans-dangling-classes` | Soot resolving levels |

Each patch is explained in [`taint/patches/README.md`](taint/patches/README.md). A jar built this
way is identical, class for class, to the one used for the measurements.

## Reproducing the thesis experiments

| What | Where |
|---|---|
| Testbed scenarios: speed-up, equivalence, seeded vulnerabilities | `experiments/` (`run_experiments.py`), on `ecommerce/backend` |
| Public systems: one run | `experiments/public/build_public.sh` builds a clone in a container; the configs are next to it |
| Commit history replay | `experiments/public/replay.sh <repo> <first> <last> <maven image>`: per commit, a container build when build input changed, an incremental run, and at the end a cold run on the last commit for the equivalence check; data goes to `$VKR_EXP/replay/<repo>/` |
| Seeded MyBatis vulnerabilities in mall-swarm | `experiments/public/seeds/` |

## Layout

```
analysis/
  vkr-taint                  entry point (sh)
  Dockerfile                 Micans from source + channel-extractor + runtime with a prebuilt JRE model
  channel-extractor/         channel model from sources (Java, Maven)
  taint/scripts/             vkr_taint.py (CLI), incremental.py (cache, composition), vkr_config.py,
                             gen_taint_config.py, summarize.py, sarif.py, run_taint.sh, prepare.sh
  taint/launcher/            Micans entry point with the vkr plugins (compiled at run time)
  taint/patches/             Micans patches and build_micans.sh
  taint/config/              configuration of the testbed
  taint/tests/               unit tests: python3 -m unittest discover -s analysis/taint/tests
  experiments/               experiment scripts and results
```
