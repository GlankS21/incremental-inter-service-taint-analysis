# Changelog

## 1.2.0

DeltaTaint 1.2.0 keeps container elements apart, makes the analysis cache small, and reruns a
service only for the configuration it is given.

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
