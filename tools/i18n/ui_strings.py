#!/usr/bin/env python3
"""DogBoard's UI text: every string literal of the settings, onboarding and panel code that reads as prose (not an id,
route, key or format), one per line, for tools/i18n/ui_translate.py. Usage: ui_strings.py > ui-strings.txt"""
import glob, re
FILES = [f for d in ('settings', 'design/settings', 'design') for f in glob.glob(f'app/src/main/kotlin/io/github/sillypuppyy/dogboard/{d}/*.kt')]
FILES += ['app/src/main/kotlin/dev/patrickgold/florisboard/app/settings/HomeScreen.kt']
LIT = re.compile(r'"((?:[^"\\$]|\\.)*)"')
seen = []
for f in sorted(set(FILES)):
    # the keyboard's own labels, per-language action words (KeyWords) and theme names (ThemeChoice) are not UI prose
    if f.endswith(('KbSpec.kt', 'UiText.kt', 'DesignLint.kt', 'KeyWords.kt', 'ThemeChoice.kt')): continue
    for line in open(f, encoding='utf-8'):
        t = line.strip()
        if t.startswith(('//', '*', 'import', 'package', '@')): continue
        for m in LIT.finditer(line):
            s = m.group(1)
            if '\\' in s or len(s) < 3 or not re.search(r'[A-Za-z]{2}', s): continue
            if re.fullmatch(r'[a-z0-9_:./#-]+', s) or s.startswith(('ui://', 'http', 'image/', 'text/', 'dogboard', 'io.')): continue
            # a sentence or label starts with a capital: lowercase ones are search keywords or pieces of built text
            if not s[0].isupper(): continue
            if re.fullmatch(r'[A-Z0-9_]+', s): continue
            if s not in seen: seen.append(s)
print('\n'.join(seen))
