# DeltaTaint

A GitHub Action that runs incremental inter-service taint analysis on Java (Spring Cloud, Maven)
microservice systems. It reports chains from an externally reachable endpoint to a sink, including
chains whose data passes through other services over HTTP (Feign, `RestTemplate`), RabbitMQ or a
shared table. On each commit it re-analyses only the services whose analysis inputs changed.

```
ep:ts-preserve-other-service:POST /api/v1/preserveotherservice/preserveOther <0/accountId>
  --HTTP /api/v1/securityservice/securityConfigs/{accountId}-->  ep:ts-security-service:GET ... <0>
  --HTTP /api/v1/orderservice/order/security/{checkDate}/{accountId}-->  ep:ts-order-service:GET ... <1>
  CWE-117 @ OrderController#securityInfoCheck:118
```

## Usage

The action does not build the project, so build it in an earlier step.

```yaml
name: Taint analysis
on:
  push:
    branches: [main]
  pull_request:

permissions:
  contents: read
  security-events: write          # SARIF upload to code scanning
  actions: read

jobs:
  taint:
    runs-on: ubuntu-latest
    timeout-minutes: 150
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21', cache: maven }
      - run: ./mvnw -B -DskipTests package
      - uses: GlankS21/web-project@v1
        with:
          path: .                 # the Maven multi-module root
```

What the action does:

- **Runs with caching.** It builds the tool image and keeps the layers in the Actions cache. It also restores the analysis cache of the latest run.
- **Compares pull requests with a baseline.** On a pull request, it compares the result with the baseline that the last push to the baseline branch stored.
- **Reports.** It uploads the chains as SARIF to code scanning and writes a summary to the job page.
- **Gates.** With `gate: 'true'`, it fails on a finding that the baseline does not have, or on a service that could not be analysed.

The first run is a full analysis, which takes minutes per service. Later runs re-analyse only the
changed services. The example workflow at [`.github/workflows/taint.yml`](.github/workflows/taint.yml)
runs the action on the testbed in this repository.

### Inputs

| Input | Default | Description |
|---|---|---|
| `path` | `.` | Project root, relative to the workspace |
| `config` | `<path>/vkr-taint.yml`, else generated | Configuration file ([reference](analysis/README.md#configuration-vkr-taintyml)) |
| `services` | all | Comma-separated subset of services |
| `gate` | `true` | Fail on new findings or on a failed service |
| `heap` | `12g` | Micans heap; a hosted runner has 16 GB |
| `upload-sarif` | `true` | Needs `security-events: write`; set `false` for pull requests from forks |
| `baseline-branch` | default branch | Pushes to this branch store the baseline |

### Outputs

| Output | Description |
|---|---|
| `findings` | Number of findings (chains of one entry point and sink method) |
| `chains` | Number of chains to a sink |
| `new` | Number of findings that the baseline does not have |
| `sarif` | Path of the SARIF file |

## Requirements and limits

- **Build:** a Maven (or Gradle-layout) Spring Cloud project, built before the action runs. A servlet war is also supported.
- **Sinks:** SQL injection (CWE-89), log injection (CWE-117), XSS (CWE-79), command injection (CWE-78), and path traversal (CWE-22). See [`analysis/README.md`](analysis/README.md#limits) for the channels that are covered and other limits.
- **Results:** a chain is a candidate, not a proof. The precision is that of Micans, which uses 1-call context sensitivity.

## Documentation

- [`analysis/README.md`](analysis/README.md): how the tool works, Docker and local use, the configuration reference, the output format, the limits, and how to reproduce the experiments.
- [`ecommerce/`](ecommerce): the microservice testbed that the method was evaluated on.

## Licence

The image builds [Micans](https://doi.org/10.5281/zenodo.15050505), which is licensed AGPL-3.0, from its published source during the action run. The Micans source and jar are not part of this repository.
