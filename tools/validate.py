#!/usr/bin/env python3
"""Kiểm tra file JSON theo schema của Mykara.

Dùng: python3 tools/validate.py project docs/project.example.json
      python3 tools/validate.py scene docs/scene.example.json
Ngoài schema, kiểm tra thêm các ràng buộc chéo mà JSON Schema không diễn đạt được.
"""
import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parent.parent


def cross_check_project(p):
    errors = []
    line_ids = [l["id"] for l in p["lyrics"]["lines"]]
    if len(set(line_ids)) != len(line_ids):
        errors.append("lyrics: id câu bị trùng")
    image_ids = {i["id"] for i in p["assets"]["images"]}
    known = set(line_ids)
    for t in p["timing"]["lines"]:
        if t["lineId"] not in known:
            errors.append(f"timing: lineId không tồn tại: {t['lineId']}")
        if t["endMs"] <= t["startMs"]:
            errors.append(f"timing: {t['lineId']} có endMs <= startMs")
    texts = {l["id"]: l["text"] for l in p["lyrics"]["lines"]}
    for d in p["design"].get("lines", []):
        if d["lineId"] not in known:
            errors.append(f"design: lineId không tồn tại: {d['lineId']}")
            continue
    sem = p["design"].get("semanticLayer", {"items": []})
    seen, used = set(), {}
    for it in sem["items"]:
        if it["id"] in seen:
            errors.append(f"semantic: id trùng: {it['id']}")
        seen.add(it["id"])
        if it["lineId"] not in known:
            errors.append(f"semantic {it['id']}: lineId không tồn tại: {it['lineId']}")
            continue
        ntok = len(texts[it["lineId"]].split())
        if it["tokenEnd"] <= it["tokenStart"] or it["tokenEnd"] > ntok:
            errors.append(f"semantic {it['id']}: token [{it['tokenStart']},{it['tokenEnd']}) ngoài phạm vi {ntok} token")
            continue
        span = it["tokenEnd"] - it["tokenStart"]
        offs = [ph["atToken"] for ph in it["phases"]]
        if offs != sorted(offs) or any(o >= span for o in offs):
            errors.append(f"semantic {it['id']}: atToken phải tăng dần và nhỏ hơn độ dài cụm ({span})")
        if it["visual"]["kind"] == "userImage" and it["visual"]["ref"] not in image_ids:
            errors.append(f"semantic {it['id']}: userImage không có trong assets.images")
        if it["enabled"] and it["textMode"] != "hidden":
            rng = set(range(it["tokenStart"], it["tokenEnd"]))
            u = used.setdefault(it["lineId"], set())
            if rng & u:
                errors.append(f"semantic {it['id']}: chồng token với mục khác trong câu {it['lineId']}")
            u |= rng
    for b in p["design"].get("background", []):
        if b["imageId"] not in image_ids:
            errors.append(f"design: imageId không tồn tại: {b['imageId']}")
        if b["toMs"] <= b["fromMs"]:
            errors.append(f"design: background {b['imageId']} có toMs <= fromMs")
    return errors


def cross_check_scene(s):
    errors = []
    ids = [l["id"] for l in s["layers"]]
    if len(set(ids)) != len(ids):
        errors.append("layers: id bị trùng")
    for l in s["layers"]:
        if l["toMs"] <= l["fromMs"]:
            errors.append(f"layer {l['id']}: toMs <= fromMs")
        if l["toMs"] > s["durationMs"]:
            errors.append(f"layer {l['id']}: vượt durationMs")
        for it in l.get("items", []):
            if it["toMs"] <= it["fromMs"]:
                errors.append(f"semantic item {it['id']}: toMs <= fromMs")
            for ph in it["phases"]:
                if not it["fromMs"] <= ph["atMs"] < it["toMs"]:
                    errors.append(f"semantic item {it['id']}: phase {ph['preset']} ngoài khoảng thời gian")
    return errors


def main(argv):
    if len(argv) != 3 or argv[1] not in ("project", "scene"):
        print(__doc__)
        return 2
    kind, path = argv[1], argv[2]
    schema = json.loads((ROOT / "schema" / f"{kind}.schema.json").read_text(encoding="utf-8"))
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    errors = [f"{'/'.join(map(str, e.absolute_path)) or '<root>'}: {e.message}"
              for e in Draft202012Validator(schema).iter_errors(data)]
    if not errors:
        errors = (cross_check_project if kind == "project" else cross_check_scene)(data)
    for e in errors:
        print("LỖI:", e)
    print("OK" if not errors else f"{len(errors)} lỗi")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
