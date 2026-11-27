"""Markdown view of an incremental.py report, for the CI job summary: the services (cached or
recomputed) and every finding (incremental.finding_key: the chains of one entry point and sink
method), one row each, with a link to the sink line when the SARIF file and the commit URL are given.
usage: report_md.py <report.json> [title] [--sarif vkr-taint.sarif --blob https://github.com/o/r/blob/<sha>]"""
import argparse
import html
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from incremental import crosses_services, group_findings, through_database  # noqa: E402
from sarif import parse, representative, source_labels  # noqa: E402


def cell(text):
    return text.replace('|', '\\|').replace('\n', ' ').replace('\r', ' ')


def code(text):
    """Text taken from the analysed sources (endpoint paths, labels, names) as a code span: the job
    summary never renders it as markdown or HTML."""
    return '`' + str(text).replace('`', "'").replace('\n', ' ').replace('\r', ' ') + '`'


def hop(edge, node):
    """One step of a chain: an HTTP call or message to another service, or a row of a shared table."""
    kind, service, name, param = node
    if kind == 'db':
        return f'{edge[0]} {code(param or name)} ({code(service)})'
    return f'{edge[0]} {code(service)} {code(name)}'


def sink_links(sarif_path, blob):
    """finding key -> link to the sink line, from the SARIF results of the same run."""
    if not (sarif_path and blob and os.path.isfile(sarif_path)):
        return {}
    links = {}
    for r in json.load(open(sarif_path, encoding='utf-8'))['runs'][0]['results']:
        loc = r['locations'][0].get('physicalLocation')
        if loc:
            line = loc.get('region', {}).get('startLine')
            links[r['properties']['finding']] = f'{blob.rstrip("/")}/{loc["artifactLocation"]["uri"]}' \
                                              + (f'#L{line}' if line else '')
    return links


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('report')
    ap.add_argument('title', nargs='?', default='Inter-service taint analysis')
    ap.add_argument('--sarif')
    ap.add_argument('--blob', help='URL prefix of the analysed commit, e.g. https://github.com/o/r/blob/<sha>')
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding='utf-8')  # the markdown has emoji; a Windows console is not UTF-8
    r = json.load(open(a.report, encoding='utf-8'))
    links = sink_links(a.sarif, a.blob)

    print(f'## {a.title}\n')
    print('| Service | Micans run | Summary | Seconds |\n|---|---|---|---|')
    for svc, s in r['services'].items():
        if s.get('failed'):
            print(f'| {svc} | **failed**: {cell(s["failed"])} | | {s["seconds"]:.0f} |')
        else:
            print(f'| {svc} | {"cached" if s["runCached"] else "recomputed"} | '
                  f'{"cached" if s["summaryCached"] else "recomputed"} | {s["seconds"]:.0f} |')

    chains = r['chains']
    findings = r.get('findings') or group_findings(chains)
    crossing, stored = sum(map(crosses_services, chains)), sum(map(through_database, chains))
    print(f'\n**{len(findings)} findings** ({len(chains)} chains to a sink: {crossing} across services, '
          f'{stored} through the database), '
          f'{r["seconds"]:.0f} s total.')
    new = set(r.get('newFindings', []))
    if 'new' not in r:
        print('No baseline: findings were not compared.')
    elif 'newFindings' in r:
        print(f'{len(r["newFindings"])} new, {len(r["removedFindings"])} removed findings against the baseline '
              f'({len(r["new"])} new, {len(r["removed"])} removed chains).')
    else:
        print(f'{len(r["new"])} new, {len(r["removed"])} removed chains against the baseline.')
    if r.get('toolChanged'):
        made_by = code(r['baselineTool']) if r.get('baselineTool') else 'version 1.4 or older'
        print(f'\n> **The baseline was made by another version of DeltaTaint** ({made_by}, now {code(r["tool"])}): '
              'a finding marked 🆕 may come from the new version rather than from this change, so new findings do '
              'not fail the gate in this run. The next push to the baseline branch stores a baseline of this '
              'version (a `gate.baseline` file has to be regenerated).')
    if r.get('failed'):
        print(f'\n> **{len(r["failed"])} service(s) not analysed, their chains are missing:**')
        for svc in r['failed']:
            s = r['services'].get(svc, {})
            log = f'; its log is {code(s["log"])} in the `vkr-taint-report` artifact' if s.get('log') else ''
            print(f'> - {code(svc)}: {html.escape(s.get("failed") or "failed")}{log}')

    if findings:
        print('\n| # | | CWE | Entry point | Source | Through | Sink | Chains |\n|---|---|---|---|---|---|---|---|')
        for i, f in enumerate(findings, 1):
            main = representative(f['chains'])
            nodes, edges, cwe, cls, method, line = parse(main)
            kind, service, name, param = nodes[0]
            sources = ', '.join(code(s) for s in source_labels(f['chains'])) or '-'
            through = ' → '.join(hop(e, n) for e, n in zip(edges, nodes[1:])) or '-'
            # another path: other hops after the entry point (another sink line of the method is not one)
            paths = len({c.split('  ', 1)[1].split('  CWE-')[0] if '-->' in c else '' for c in f['chains']})
            if paths > 1:
                through += f' (+{paths - 1} other path{"s" if paths > 2 else ""})'
            lines = sorted({parse(c)[5] for c in f['chains']})
            sink = f'{cls}#{method}:{",".join(map(str, lines))}'
            sink = f'[{sink}]({links[f["key"]]})' if f['key'] in links else code(sink)
            mark = '🆕' if f['key'] in new else ''
            print(f'| {i} | {mark} | {cwe} | {cell(f"{code(service)} {code(name)}")} | {cell(sources)} | {cell(through)} '
                  f'| {sink} | {len(f["chains"])} |')
    for title, items in (('Removed findings', r.get('removedFindings', r.get('removed'))),
                         ('Not re-checked (service failed)', r.get('unchecked'))):
        if items:
            print(f'\n<details><summary>{title}: {len(items)}</summary>\n\n<pre>')
            print(html.escape('\n'.join(items)))
            print('</pre>\n</details>')


if __name__ == '__main__':
    main()
