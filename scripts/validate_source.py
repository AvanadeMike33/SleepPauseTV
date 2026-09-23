#!/usr/bin/env python3
"""Offline structural and regression checks for the Android project."""
from collections import Counter
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def strip_kotlin_literals(text: str) -> str:
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


kotlin_files = list(ROOT.rglob('*.kt'))
for kotlin in kotlin_files:
    check_balanced(kotlin)

for xml in ROOT.rglob('*.xml'):
    try:
        ET.parse(xml)
    except ET.ParseError as exc:
        errors.append(f"{xml}: {exc}")

all_text = '\n'.join(
    path.read_text(encoding='utf-8', errors='ignore')
    for path in ROOT.rglob('*')
    if path.is_file() and path.suffix != '.tflite'
)

required_patterns = {
    'configured dB threshold is authoritative': r'val effectiveThreshold\s*=\s*config\.thresholdDb',
    'startup calibration': r'calibrationFrames\s*:\s*Int\s*=\s*6',
    'refractory period': r'refractoryFrames\s*:\s*Int\s*=\s*4',
    'snoring threshold': r'snoringConfidence:\s*Float\s*=\s*0\.45f',
    'breathing threshold': r'breathingConfidence:\s*Float\s*=\s*0\.50f',
    'snoring sequence': r'snoringConsecutiveDetections:\s*Int\s*=\s*2',
    'breathing sequence': r'breathingConsecutiveDetections:\s*Int\s*=\s*4',
    'autonomous breathing confirmation': r'snoringReady\s*&&\s*breathingReady\s*->\s*SleepEvidence\.COMBINED',
    'breathing autonomy regression test': r'breathingConfirmationDoesNotRequireConfirmedSnoring',
    'breathing false-positive regression test': r'autonomousBreathingStillUsesTheFalsePositiveFilter',
    'other-sound sensitivity': r'otherSoundSensitivity:\s*Float\s*=\s*0\.50f',
    'no-movement setting': r'noMovementMinutes:\s*Int\s*=\s*5',
    'movement pause gate': r'PausePolicy\.evaluate',
    'recent movement timestamp input': r'lastMotionAt\s*=\s*lastMotionAt',
    'TV pause after eligibility': r'if\s*\(latestEligibility\.allowed\)\s*container\.tvClient\.sendPause\(\)',
    'Samsung adapter': r'KEY_PAUSE',
    'LG adapter': r'ssap://media\.controls/pause',
    'Roku adapter': r'keypress/Play',
    'Home Assistant adapter': r'media_player/media_pause',
    'fresh session state': r'MonitorState\(',
    'immediate event count': r'sleepEventCount\s*=\s*newSleepEventCount',
    'serialized TV pause': r'pauseMutex\.withLock',
    'stored pause-result update': r'updateSleepEvent',
    'reusable TV connection': r'readyForCommands',
    'stale pairing recovery': r'isAuthorizationRejected|isPairingRejected',
    'Home Assistant bearer token': r'authorizationHeader\(config\.homeAssistantToken\)',
    'Samsung legacy fallback': r'port\s*=\s*if\s*\(secure\)\s*8002\s*else\s*8001',
    'LG secure fallback': r'port\s*=\s*if\s*\(secure\)\s*3001\s*else\s*3000',
}
for label, pattern in required_patterns.items():
    if not re.search(pattern, all_text):
        errors.append(f"Missing {label}")

ui_text = (ROOT / 'app/src/main/java/it/michelegiammarini/sleeppausetv/ui/SleepPauseScreen.kt').read_text()
banned_ui_words = ['Monitoraggio', 'Russamenti', 'Connetti', 'Nessuna sessione', 'Termina e salva']
for word in banned_ui_words:
    if word in ui_text:
        errors.append(f"Untranslated UI text: {word}")

for path in ROOT.rglob('*.kt'):
    text = path.read_text(encoding='utf-8', errors='ignore')
    if re.search(r'<\s*(html|body|div|span|script|style)\b', text, re.I):
        errors.append(f"HTML markup found in Kotlin source: {path}")

class_names: list[str] = []
for path in ROOT.glob('app/src/main/java/**/*.kt'):
    text = strip_kotlin_literals(path.read_text(encoding='utf-8'))
    class_names.extend(re.findall(r'\b(?:data\s+class|enum\s+class|class|object|interface)\s+(\w+)', text))
for name, count in Counter(class_names).items():
    if count > 1:
        errors.append(f"Duplicate production declaration: {name} ({count})")

if errors:
    print('FAILED')
    print('\n'.join(f'- {error}' for error in errors))
    sys.exit(1)

print(
    f"OK: {len(kotlin_files)} Kotlin files and "
    f"{len(list(ROOT.rglob('*.xml')))} XML files passed structural and regression checks"
)
