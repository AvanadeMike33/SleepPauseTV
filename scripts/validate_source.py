#!/usr/bin/env python3
"""Offline structural validation for environments without Android SDK/Gradle."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def strip_kotlin_literals(text: str) -> str:
    # Preserve line structure while removing comments and quoted content.
    text = re.sub(r'/\*.*?\*/', lambda m: '\n' * m.group(0).count('\n'), text, flags=re.S)
    text = re.sub(r'""".*?"""', lambda m: '\n' * m.group(0).count('\n'), text, flags=re.S)
    text = re.sub(r'"(?:\\.|[^"\\])*"', '""', text)
    text = re.sub(r"'(?:\\.|[^'\\])'", "''", text)
    text = re.sub(r'//[^\n]*', '', text)
    return text


def check_balanced(path: Path) -> None:
    cleaned = strip_kotlin_literals(path.read_text(encoding='utf-8'))
    pairs = {'(': ')', '[': ']', '{': '}'}
    reverse = {v: k for k, v in pairs.items()}
    stack: list[tuple[str, int]] = []
    for line_no, line in enumerate(cleaned.splitlines(), 1):
        for char in line:
            if char in pairs:
                stack.append((char, line_no))
            elif char in reverse:
                if not stack or stack[-1][0] != reverse[char]:
                    errors.append(f"{path}: unmatched {char} on line {line_no}")
                    return
                stack.pop()
    if stack:
        char, line_no = stack[-1]
        errors.append(f"{path}: unclosed {char} from line {line_no}")


for kotlin in ROOT.rglob('*.kt'):
    check_balanced(kotlin)

for xml in ROOT.rglob('*.xml'):
    try:
        ET.parse(xml)
    except ET.ParseError as exc:
        errors.append(f"{xml}: {exc}")

all_text = '\n'.join(
    p.read_text(encoding='utf-8', errors='ignore')
    for p in ROOT.rglob('*')
    if p.is_file() and p.suffix != '.tflite'
)

required_patterns = {
    'adaptive noise gate': r'minSnrDb\s*:\s*Float\s*=\s*6f',
    'startup calibration': r'calibrationFrames\s*:\s*Int\s*=\s*6',
    'refractory period': r'refractoryFrames\s*:\s*Int\s*=\s*4',
    'balanced confidence': r'minConfidence:\s*Float\s*=\s*0\.45f',
    'Samsung adapter': r'KEY_PAUSE',
    'LG adapter': r'ssap://media\.controls/pause',
    'Roku adapter': r'keypress/Play',
    'Home Assistant adapter': r'media_player/media_pause',
    'fresh session state': r'MonitorState\(running\s*=\s*true',
}
for label, pattern in required_patterns.items():
    if not re.search(pattern, all_text):
        errors.append(f"Missing {label}")

banned_ui_words = ['Monitoraggio', 'Russamenti', 'Connetti', 'Nessuna sessione', 'Termina e salva']
ui_text = (ROOT / 'app/src/main/java/it/michelegiammarini/sleeppausetv/ui/SleepPauseScreen.kt').read_text()
for word in banned_ui_words:
    if word in ui_text:
        errors.append(f"Untranslated UI text: {word}")

if errors:
    print('FAILED')
    print('\n'.join(f'- {error}' for error in errors))
    sys.exit(1)

print(f"OK: {len(list(ROOT.rglob('*.kt')))} Kotlin files and {len(list(ROOT.rglob('*.xml')))} XML files passed structural checks")
