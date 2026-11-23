"""Reads `android layout` JSON on stdin and prints one line per element with text or an interaction.

Usage: android layout --device=SERIAL | python -I list_screen.py
"""
import json
import sys


def main():
    raw = sys.stdin.read()
    raw = raw[raw.index('['):]

    def walk(node, out):
        if isinstance(node, list):
            for child in node:
                walk(child, out)
        elif isinstance(node, dict):
            text = node.get('text') or node.get('content-desc')
            if text or node.get('interactions'):
                out.append(f"{(text or '')[:70]!r} {node.get('center')} "
                           f"{','.join(node.get('interactions', []))} {','.join(node.get('state', []))}")
            for key in ('content', 'children'):
                if key in node:
                    walk(node[key], out)

    for window in json.loads(raw):
        if window.get('type') == 'SYSTEM' and not window.get('active'):
            continue  # the status bar
        print('==', window.get('window-title'), window.get('type'))
        lines = []
        walk(window.get('content', []), lines)
        print('\n'.join(lines))


if __name__ == '__main__':
    main()
