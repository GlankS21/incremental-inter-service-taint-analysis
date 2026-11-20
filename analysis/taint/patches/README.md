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

## How to apply

```sh
cd <micans-source>
patch -p1 < <repo>/analysis/taint/patches/micans-world-cache.patch
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
