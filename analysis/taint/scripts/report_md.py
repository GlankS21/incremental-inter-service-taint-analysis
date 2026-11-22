"""Markdown view of an incremental.py report, for the CI job summary: the services (cached or
recomputed) and every chain to a sink, one row each, with a link to the sink line when the SARIF
file and the commit URL are given.
usage: report_md.py <report.json> [title] [--sarif vkr-taint.sarif --blob https://github.com/o/r/blob/<sha>]"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sarif import chain_key, parse  # noqa: E402


def cell(text):
    return text.replace('|', '\\|')


def hop(edge, node):
    """One step of a chain: an HTTP call or message to another service, or a row of a shared table."""
    kind, service, name, param = node
    if kind == 'db':
        return f'{edge[0]} `{param or name}` ({service})'
    return f'{edge[0]} `{service}` {name}'


def sink_links(sarif_path, blob):
    """chain -> link to the sink line, from the SARIF results of the same run."""
    if not (sarif_path and blob and os.path.isfile(sarif_path)):
        return {}
    links = {}
    for r in json.load(open(sarif_path, encoding='utf-8'))['runs'][0]['results']:
        loc = r['locations'][0].get('physicalLocation')
        if loc:
            line = loc.get('region', {}).get('startLine')
            links[r['properties']['chain']] = f'{blob.rstrip("/")}/{loc["artifactLocation"]["uri"]}' \
                                              + (f'#L{line}' if line else '')
    return links


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('report')
    ap.add_argument('title', nargs='?', default='Inter-service taint analysis')
    ap.add_argument('--sarif')
    ap.add_argument('--blob', help='URL prefix of the analysed commit, e.g. https://github.com/o/r/blob/<sha>')
    a = ap.parse_args()
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
    crossing = sum('-->' in c for c in chains)
    print(f'\n**{len(chains)} chains to a sink** ({crossing} across a channel), {r["seconds"]:.0f} s total.')
    new = {chain_key(c) for c in r.get('new', [])}
    if 'new' not in r:
        print('No baseline: chains were not compared.')
    else:
        print(f'{len(r["new"])} new, {len(r["removed"])} removed against the baseline.')
    if r.get('failed'):
        print(f'\n> **{len(r["failed"])} service(s) not analysed, their chains are missing:** {", ".join(r["failed"])}')

    if chains:
        print('\n| # | | CWE | Entry point | Through | Sink |\n|---|---|---|---|---|---|')
        for i, c in enumerate(chains, 1):
            nodes, edges, cwe, cls, method, line = parse(c)
            kind, service, name, param = nodes[0]
            entry = f'`{service}` {name}' + (f' `<{param}>`' if param else '')
            through = ' → '.join(hop(e, n) for e, n in zip(edges, nodes[1:])) or '-'
            sink = f'{cls}#{method}:{line}'
            sink = f'[{sink}]({links[c]})' if c in links else f'`{sink}`'
            mark = '🆕' if chain_key(c) in new else ''
            print(f'| {i} | {mark} | {cwe} | {cell(entry)} | {cell(through)} | {sink} |')
    for title, key in (('Removed chains', 'removed'), ('Not re-checked (service failed)', 'unchecked')):
        if r.get(key):
            print(f'\n<details><summary>{title}: {len(r[key])}</summary>\n\n```')
            print('\n'.join(r[key]))
            print('```\n</details>')


if __name__ == '__main__':
    main()
