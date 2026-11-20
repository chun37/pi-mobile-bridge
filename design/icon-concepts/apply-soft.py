#!/usr/bin/env python3
"""Generate Android adaptive icons from the selected SOFT SVG. Standard library only."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
RES = ROOT.parents[1] / "android/app/src/main/res"
ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)


def a(name):
    return f"{{{ANDROID}}}{name}"


def write_xml(path, root):
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.indent(root, space="    ")
    path.write_text(ET.tostring(root, encoding="unicode") + "\n")


source = ET.parse(ROOT / "01-soft.svg").getroot()
background = source.find("{http://www.w3.org/2000/svg}rect").get("fill")
for monochrome in (False, True):
    vector = ET.Element("vector", {
        a("width"): "108dp", a("height"): "108dp",
        a("viewportWidth"): "512", a("viewportHeight"): "512",
    })
    # Adaptive layers extend beyond the mask. Keep the mark comfortably inside it.
    group = ET.SubElement(vector, "group", {
        a("pivotX"): "256", a("pivotY"): "256", a("scaleX"): "0.8", a("scaleY"): "0.8",
    })

    def paths(node, inherited):
        style = {**inherited, **node.attrib}
        tag = node.tag.rsplit("}", 1)[-1]
        if tag == "path":
            ET.SubElement(group, "path", {
                a("pathData"): node.attrib["d"], a("fillColor"): "#00000000",
                a("strokeColor"): "#FFFFFFFF" if monochrome else style["stroke"],
                a("strokeWidth"): style["stroke-width"],
                a("strokeLineCap"): style.get("stroke-linecap", "butt"),
                a("strokeLineJoin"): style.get("stroke-linejoin", "miter"),
            })
        elif tag == "g":
            for child in node:
                paths(child, style)
        elif tag not in {"rect", "title"}:
            raise ValueError(f"Unsupported SVG element: {tag}")

    for child in source:
        paths(child, {})
    name = "ic_launcher_monochrome" if monochrome else "ic_launcher_foreground"
    write_xml(RES / "drawable" / f"{name}.xml", vector)

colors = ET.Element("resources")
ET.SubElement(colors, "color", {"name": "launcher_background"}).text = background
write_xml(RES / "values" / "icon_colors.xml", colors)
for version in (26, 33):
    icon = ET.Element("adaptive-icon")
    ET.SubElement(icon, "background", {a("drawable"): "@color/launcher_background"})
    ET.SubElement(icon, "foreground", {a("drawable"): "@drawable/ic_launcher_foreground"})
    if version >= 33:
        ET.SubElement(icon, "monochrome", {a("drawable"): "@drawable/ic_launcher_monochrome"})
    write_xml(RES / f"mipmap-anydpi-v{version}" / "ic_launcher.xml", icon)
print("Generated SOFT adaptive icon, including Android 13+ monochrome layer")
