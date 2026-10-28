#!/usr/bin/env python3
"""Lightweight static accessibility/HTML regression audit for the gateway dashboard."""
from __future__ import annotations
from collections import Counter
from html.parser import HTMLParser
from pathlib import Path
import re
import sys

HTML = Path(__file__).resolve().parents[1] / "src/main/resources/static/index.html"
CSS = Path(__file__).resolve().parents[1] / "src/main/resources/static/phase17-18.css"

class Audit(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.ids=[]; self.images=[]; self.controls=[]; self.labels=[]
        self._label_depth=0; self._label_text=[]; self._button_depth=0; self._button_text=[]
        self.buttons=[]; self.tables=0
    def handle_starttag(self, tag, attrs):
        a=dict(attrs)
        if a.get('id'): self.ids.append(a['id'])
        if tag == 'img': self.images.append((a.get('src',''), a.get('alt')))
        if tag == 'label':
            self._label_depth += 1
            if a.get('for'): self.labels.append(a['for'])
        if tag in {'input','select','textarea'} and a.get('type','').lower() not in {'hidden','submit','button','reset','image'}:
            self.controls.append((tag,a.get('id'),a.get('aria-label'),a.get('aria-labelledby'),a.get('type','text')))
        if tag == 'button':
            self._button_depth += 1
            if self._button_depth == 1: self._button_text=[]; self.buttons.append((a.get('aria-label'),a.get('title')))
        if tag == 'table': self.tables += 1
    def handle_endtag(self, tag):
        if tag == 'label' and self._label_depth: self._label_depth -= 1
        if tag == 'button' and self._button_depth:
            self._button_depth -= 1
            if self._button_depth == 0 and self._button_text:
                label, title = self.buttons[-1]
                if not label and not title: self.buttons[-1] = (' '.join(self._button_text).strip(), None)
    def handle_data(self, data):
        t=data.strip()
        if not t: return
        if self._label_depth: self.labels.append(('__nested__',t))
        if self._button_depth: self._button_text.append(t)

def main():
    parser=Audit(); parser.feed(HTML.read_text(encoding='utf-8'))
    counts=Counter(parser.ids); duplicate=sorted(k for k,v in counts.items() if v>1)
    missing_alt=[src for src,alt in parser.images if alt is None]
    nested=any(item[0]=='__nested__' for item in parser.labels)
    labelled=set(x for x in parser.labels if isinstance(x,str))
    missing_labels=[(tag,i,typ) for tag,i,aria,by,typ in parser.controls if not (aria or by or (i and i in labelled) or nested)]
    unnamed=[i for i,(aria,title) in enumerate(parser.buttons,1) if not (aria or title)]
    css=CSS.read_text(encoding='utf-8')
    checks={
      'visible_focus_rule': bool(re.search(r':focus-visible',css)),
      'reduced_motion_rule': '@media (prefers-reduced-motion: reduce)' in css,
      'forced_colors_support': '@media (forced-colors: active)' in css,
    }
    print(f'HTML={HTML.name} elements_with_ids={len(parser.ids)} tables={parser.tables}')
    print(f'duplicate_ids={duplicate or "none"}')
    print(f'images_without_alt={missing_alt or "none"}')
    print(f'controls_without_explicit_label={missing_labels[:20] or "none detected by static heuristic"}')
    print(f'buttons_without_static_name={unnamed[:20] or "none detected by static heuristic"}')
    print('CSS checks='+repr(checks))
    if duplicate or missing_alt or not checks['visible_focus_rule'] or not checks['reduced_motion_rule']:
        return 1
    print('PASS: core static structural checks. This is not a substitute for NVDA/TalkBack, keyboard-only, zoom and contrast manual testing.')
    return 0
if __name__ == '__main__': raise SystemExit(main())
