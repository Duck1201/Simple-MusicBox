#!/usr/bin/env python3
"""Verificações estáticas dos recursos do mod (rodadas no CI).

- todo .json em src/ é JSON válido
- en_us e pt_br têm exatamente as mesmas chaves
- toda chave de tradução citada no código Java existe no en_us
- arquivos e classes referenciados pelo fabric.mod.json e pelos mixins existem
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src"
LANG = SRC / "main/resources/assets/simple_musicbox/lang"
SOURCE_SETS = [SRC / "main", SRC / "client"]
KEY_LITERAL = re.compile(r'"((?:item\.|jukebox_song\.)?simple_musicbox\.[a-z0-9_.]+)"')

errors = []


def load_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        errors.append(f"{path.relative_to(ROOT)}: JSON inválido ({e})")
        return None


def java_class_exists(name):
    rel = name.replace(".", "/") + ".java"
    return any((s / "java" / rel).is_file() for s in SOURCE_SETS)


def resource_exists(rel):
    return any((s / "resources" / rel).is_file() for s in SOURCE_SETS)


# 1. JSON válido
for path in sorted(SRC.rglob("*.json")):
    load_json(path)

# 2. Paridade de traduções
en = load_json(LANG / "en_us.json") or {}
pt = load_json(LANG / "pt_br.json") or {}
for key in sorted(set(en) - set(pt)):
    errors.append(f"pt_br.json: falta a chave {key}")
for key in sorted(set(pt) - set(en)):
    errors.append(f"en_us.json: falta a chave {key}")

# 3. Chaves usadas no código
for java in sorted(SRC.rglob("*.java")):
    for lineno, line in enumerate(java.read_text(encoding="utf-8").splitlines(), 1):
        for key in KEY_LITERAL.findall(line):
            if key not in en:
                errors.append(f"{java.relative_to(ROOT)}:{lineno}: chave de tradução inexistente {key}")

# 4. fabric.mod.json e mixins
mod = load_json(SRC / "main/resources/fabric.mod.json") or {}
if mod.get("icon") and not resource_exists(mod["icon"]):
    errors.append(f"fabric.mod.json: ícone {mod['icon']} não existe")
for side, entries in mod.get("entrypoints", {}).items():
    for entry in entries:
        name = entry if isinstance(entry, str) else entry.get("value", "")
        if not java_class_exists(name.split("::")[0]):
            errors.append(f"fabric.mod.json: entrypoint {side} {name} não existe")
for mixin in mod.get("mixins", []):
    config_name = mixin if isinstance(mixin, str) else mixin["config"]
    config_path = next((s / "resources" / config_name for s in SOURCE_SETS
                        if (s / "resources" / config_name).is_file()), None)
    if config_path is None:
        errors.append(f"fabric.mod.json: config de mixin {config_name} não existe")
        continue
    config = load_json(config_path) or {}
    for group in ("mixins", "client", "server"):
        for cls in config.get(group, []):
            if not java_class_exists(f"{config['package']}.{cls}"):
                errors.append(f"{config_name}: mixin {cls} não existe")

if errors:
    print("\n".join(errors))
    sys.exit(1)
print("Recursos OK")
