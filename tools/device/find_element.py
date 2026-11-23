"""Reads `android layout` JSON on stdin and prints "x y" for the element showing the given text.

Usage: android layout --device=SERIAL | python -I find_element.py "Send Code" [nth]

The centre printed is the nearest clickable or focusable ancestor's, because Compose often puts the text in a child
of the element that takes the tap. Exits with an error when nothing matches.
"""
import json
import sys


def main():
    want = sys.argv[1]
    nth = int(sys.argv[2]) if len(sys.argv) > 2 else 0
    raw = sys.stdin.read()
    raw = raw[raw.index('['):]  # the CLI can print a status line first
    hits = []

    def walk(node, tappable_centre=None):
        if isinstance(node, list):
            for child in node:
                walk(child, tappable_centre)
            return
        if not isinstance(node, dict):
            return
        interactions = node.get('interactions', [])
        centre = node.get('center') if 'CLICKABLE' in interactions or 'FOCUSABLE' in interactions else tappable_centre
        text = node.get('text') or node.get('content-desc') or ''
        if text == want and not node.get('off-screen'):
            hits.append(centre or node.get('center'))
        for key in ('content', 'children'):
            if key in node:
                walk(node[key], centre)

    for window in json.loads(raw):
        walk(window.get('content', []))
    if len(hits) <= nth:
        sys.exit(f'not found: {want!r}')
    print(hits[nth].strip('[]').replace(',', ' '))


if __name__ == '__main__':
    main()
