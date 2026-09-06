#!/usr/bin/env python3
"""Convert a module README.md into pure-markdown README_nohtml.md for CurseForge.

CurseForge's description editor only accepts markdown (no raw HTML), so the
hand-written HTML in README.md (centering divs, screenshot tables, <img> badges)
has to be lowered to plain markdown. Badges are kept as markdown badges
([![alt](img)](link)) and relative image paths are rewritten to absolute raw
GitHub URLs so they resolve off-site, the same way sync_descriptions.yml does
for the Modrinth body: "../" resolves against the repo root, a bare path
resolves against this module's own directory.

Usage: readme_to_markdown.py <input.md> <output.md> <module_dir>
"""
import re
import sys

RAW_BASE = "https://raw.githubusercontent.com/duzos/bluemap3d/master"


def abs_url(src, module_dir):
    src = src.strip()
    if src.startswith(("http://", "https://", "//")):
        return src
    if src.startswith("../"):
        return RAW_BASE + "/" + src[3:]
    return RAW_BASE + "/" + module_dir + "/" + src.lstrip("./")


def img_to_md(m, module_dir):
    attrs = m.group(1)
    src = re.search(r'src\s*=\s*"([^"]*)"', attrs)
    alt = re.search(r'alt\s*=\s*"([^"]*)"', attrs)
    return "![{}]({})".format(alt.group(1) if alt else "", abs_url(src.group(1), module_dir) if src else "")


def convert(text, module_dir):
    # <img> (incl. those wrapped in [..](url) badge links) -> markdown image
    text = re.sub(r"<img\b([^>]*?)/?>", lambda m: img_to_md(m, module_dir), text)
    # line breaks -> newline
    text = re.sub(r"<br\s*/?>", "\n", text)
    # inline emphasis
    text = text.replace("<b>", "**").replace("</b>", "**")
    text = text.replace("<i>", "*").replace("</i>", "*")
    # drop sub/sup wrappers, keep their text
    text = re.sub(r"</?su[bp]\b[^>]*>", "", text)
    # table cells become their own lines, other layout tags are dropped
    text = re.sub(r"</?td\b[^>]*>", "\n", text)
    text = re.sub(r"</?(div|table|tbody|thead|tr)\b[^>]*>", "", text)
    # entities
    text = text.replace("&nbsp;", " ")
    # tidy: strip trailing spaces, collapse blank runs
    text = "\n".join(line.rstrip() for line in text.splitlines())
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip() + "\n"


def main():
    if len(sys.argv) != 4:
        sys.exit("usage: readme_to_markdown.py <input.md> <output.md> <module_dir>")
    module_dir = sys.argv[3]
    with open(sys.argv[1], encoding="utf-8") as f:
        out = convert(f.read(), module_dir)
    with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as f:
        f.write(out)


if __name__ == "__main__":
    main()
