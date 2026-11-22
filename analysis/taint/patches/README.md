# Micans patches (ADR-TCI-3)

`micans-world-cache.patch` is applied to the Micans source extract
(`vkr-artifacts/miscans/src-extract/micans-source`, where `build/micans.jar` is built) before the
jar is rebuilt. The unpatched jar is kept as `build/micans-original.jar` and backs the recorded
measurements.

## What it changes

1. `CachedWorldBuilder.getWorldCacheHash` — the world-cache key hashes the **content** of every
   classpath entry (a jar's bytes, or the size and path of every file below a directory) and of
   the JRE model directory, instead of `File.lastModified`. Re-copying a workdir with fresh
   timestamps therefore still hits the cache.
2. `CachedWorldBuilder.build` — with the system property `vkr.worldCacheReadOnly=true` the
   serialized World is **loaded but never saved** (a CI run on a PR must not write cache files).
3. `TaintAnalysis.onFinish` — the taint-flow-graph dump is skipped when
   `vkr.skipTaintGraph=true`; the CI path reads only the `TaintFlow{`/`StoreFlow{` lines.
   The benchmark and verification runs leave the property unset and keep the `.dot` graphs.

## `micans-phantom-beans.patch`

`SpringIocAnalysis.createBeans` makes custom beans of `JdbcTemplate` and `RedisTemplate` whenever the
class hierarchy has the class. A service that does not package spring-jdbc still sees `JdbcTemplate`
as a phantom class (a library references it), which has no constructor, and the analysis fails with
"No matching bean constructor found" (train-ticket, ts-cancel-service). The patch skips phantom
classes there. It does not change a run on a service that packages the class (the ecommerce
testbed). The jar built before it is kept as `build/micans-tci3.jar`.

## `micans-static-base-transfer.patch`

The generated configs carry wildcard accessor transfers `<pkg.*: * *()>` from `base` to `result`
for every DTO and event package. In the ecommerce testbed these packages hold records only, but a
Lombok class also has a static no-argument `builder()`, which the wildcard matches; a static call
has no base variable and `TransferHandler.processTransfer` failed with a `ClassCastException`
(train-ticket ts-preserve-other-service, mall-swarm mall-admin). The patch skips a transfer from or
to `base` at a static call site; any run that did not crash is unchanged. The jar built before it
is kept as `build/micans-phantom.jar`.

## `micans-dangling-classes.patch`

With phantom references allowed, Soot leaves a class that a library only names in a signature at
resolving level DANGLING. Soot's `jb` typing of a library body that stores into such a type then
throws ("requires resolving level HIERARCHY but com.codahale.metrics.Gauge is at resolving level
DANGLING", HikariCP's `CodaHaleMetricsTracker` without metrics-core; mall-swarm at 9ed1453, 4 of 6
services). The patch sets Soot's `ignore_resolving_levels` together with `allow_phantom_refs`; a run
that did not crash is unchanged. The jar built before it is kept as `build/micans-static.jar`.

## How to apply

`build_micans.sh <out micans.jar> [micans-source.tar.xz]` does all of it: it downloads the source
from Zenodo (10.5281/zenodo.15050505, AGPL-3.0) unless a copy is given, checks its sha256, applies
the four patches in this order, and builds with Gradle (JAVA_HOME: JDK 17). The Dockerfile uses it.
By hand:

```sh
cd <micans-source>
patch -p1 < <repo>/analysis/taint/patches/micans-world-cache.patch
patch -p1 < <repo>/analysis/taint/patches/micans-phantom-beans.patch
patch -p1 < <repo>/analysis/taint/patches/micans-static-base-transfer.patch
patch -p1 < <repo>/analysis/taint/patches/micans-dangling-classes.patch
JAVA_HOME=<jdk-24> sh gradlew packageMicans   # writes build/micans.jar
```

## How it is wired

- `run_taint.sh` passes `-Dvkr.worldCache=$VKR_WORLD_CACHE` (`off` by default, `rw`/`ro` in CI)
  and `-Dvkr.skipTaintGraph=${VKR_SKIP_TFG:-true}`.
- `MicansTaintMain` adds `--world-cache-mode` when `vkr.worldCache` is not `off` and sets
  `vkr.worldCacheReadOnly` for `ro`.
- `whole_system.py` never sets these, and `micans_benchmark.py` sets them only with
  `VKR_WORLD_CACHE=rw|ro`: then both the per-service runs and the whole-system Micans run get the
  world cache and skip the graph dump (a fair timing baseline), and the rows go to `runs-*-wc/`.
  Without it the runs stay comparable with Micans as published and keep the graphs the verified
  flows are read from.
- Cache files: `<cwd>/cache/world-cache-<hash>.bin`, where the working directory is the JRE-home
  prepared per work dir. One file per distinct classpath; the serialized World is large, so the
  CI runner needs a disk budget and an eviction policy (ADR-TCI-3).
