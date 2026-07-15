#!/usr/bin/env python3
"""Render generic or document-capable Android wrapper manifests."""

import argparse
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "http://schemas.android.com/apk/res/android"
NAME = f"{{{ANDROID}}}name"
MIME_TYPE = f"{{{ANDROID}}}mimeType"
VIEW = "android.intent.action.VIEW"
EDIT = "android.intent.action.EDIT"
MIME_SLOT_COUNT = 16

ET.register_namespace("android", ANDROID)


def document_filter(activity: ET.Element) -> ET.Element:
    matches = []
    for intent_filter in activity.findall("intent-filter"):
        actions = {node.get(NAME, "") for node in intent_filter.findall("action")}
        if VIEW in actions or EDIT in actions:
            matches.append(intent_filter)
    if len(matches) != 1:
        raise ValueError(f"expected one document intent filter, found {len(matches)}")
    return matches[0]


def render(source: Path, output: Path, profile: str) -> None:
    tree = ET.parse(source)
    root = tree.getroot()
    activities = root.findall("./application/activity")
    main = next((node for node in activities if node.get(NAME, "").endswith("MainActivity")), None)
    if main is None:
        raise ValueError("wrapper MainActivity is missing")
    intent_filter = document_filter(main)
    if profile == "generic":
        main.remove(intent_filter)
    else:
        for data in list(intent_filter.findall("data")):
            intent_filter.remove(data)
        for index in range(MIME_SLOT_COUNT):
            ET.SubElement(intent_filter, "data", {
                MIME_TYPE: f"application/x-archphene-mime-{index:02d}"
            })
    ET.indent(tree, space="    ")
    tree.write(output, encoding="utf-8", xml_declaration=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", choices=("generic", "document"), required=True)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    render(args.source, args.output, args.profile)


if __name__ == "__main__":
    main()
