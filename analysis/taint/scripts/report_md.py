"""Markdown view of an incremental.py report, for the CI job summary: the services (cached or
recomputed) and every finding (incremental.finding_key: the chains of one entry point and sink
method), one row each, with a link to the sink line when the SARIF file and the commit URL are given.
usage: report_md.py <report.json> [title] [--sarif vkr-taint.sarif --blob https://github.com/o/r/blob/<sha>]"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from incremental import group_findings  # noqa: E402
from sarif import parse, representative, source_labels  # noqa: E402


def cell(text):
    return text.replace('|', '\\|')


def hop(edge, node):
    """One step of a chain: an HTTP call or message to another service, or a row of a shared table."""
    kind, service, name, param = node
    if kind == 'db':
        return f'{edge[0]} `{param or name}` ({service})'
    return f'{edge[0]} `{service}` {name}'


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
    crossing = sum('-->' in c for c in chains)
    print(f'\n**{len(findings)} findings** ({len(chains)} chains to a sink, {crossing} across a channel), '
          f'{r["seconds"]:.0f} s total.')
    new = set(r.get('newFindings', []))
    if 'new' not in r:
        print('No baseline: findings were not compared.')
    elif 'newFindings' in r:
        print(f'{len(r["newFindings"])} new, {len(r["removedFindings"])} removed findings against the baseline '
              f'({len(r["new"])} new, {len(r["removed"])} removed chains).')
    else:
        print(f'{len(r["new"])} new, {len(r["removed"])} removed chains against the baseline.')
    if r.get('failed'):
        print(f'\n> **{len(r["failed"])} service(s) not analysed, their chains are missing:** {", ".join(r["failed"])}')

    if findings:
        print('\n| # | | CWE | Entry point | Source | Through | Sink | Chains |\n|---|---|---|---|---|---|---|---|')
        for i, f in enumerate(findings, 1):
            main = representative(f['chains'])
            nodes, edges, cwe, cls, method, line = parse(main)
            kind, service, name, param = nodes[0]
            sources = ', '.join(f'`{s}`' for s in source_labels(f['chains'])) or '-'
            through = ' → '.join(hop(e, n) for e, n in zip(edges, nodes[1:])) or '-'
            # another path: other hops after the entry point (another sink line of the method is not one)
            paths = len({c.split('  ', 1)[1].split('  CWE-')[0] if '-->' in c else '' for c in f['chains']})
            if paths > 1:
                through += f' (+{paths - 1} other path{"s" if paths > 2 else ""})'
            lines = sorted({parse(c)[5] for c in f['chains']})
            sink = f'{cls}#{method}:{",".join(map(str, lines))}'
            sink = f'[{sink}]({links[f["key"]]})' if f['key'] in links else f'`{sink}`'
            mark = '🆕' if f['key'] in new else ''
            print(f'| {i} | {mark} | {cwe} | {cell(f"`{service}` {name}")} | {cell(sources)} | {cell(through)} '
                  f'| {sink} | {len(f["chains"])} |')
    for title, items in (('Removed findings', r.get('removedFindings', r.get('removed'))),
                         ('Not re-checked (service failed)', r.get('unchecked'))):
        if items:
            print(f'\n<details><summary>{title}: {len(items)}</summary>\n\n```')
            print('\n'.join(items))
            print('```\n</details>')


if __name__ == '__main__':
    main()
