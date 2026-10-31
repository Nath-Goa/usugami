"""
Collects the open-source license notices that Google ships inside its ML Kit, Google Play
services, Firebase and DataTransport libraries (third_party_licenses.json/.txt in each AAR) and
writes them to third_party/, deduplicated:

  third_party/google-notices.txt          libraries in the standard edition (and the offline one)
  third_party/google-notices-offline.txt  libraries that only the offline edition contains

Identical texts are written once, a notice that is fully contained in a longer variant of the same
component is dropped, and every verbatim copy of the Apache License 2.0 is replaced by a pointer to
the copy in THIRD_PARTY_NOTICES.md. Nothing else is changed.

Run it after changing Google dependencies (Gradle must have downloaded them first):

    ./gradlew -q :app:dependencies --configuration standardReleaseRuntimeClasspath > deps-standard.txt
    ./gradlew -q :app:dependencies --configuration offlineReleaseRuntimeClasspath > deps-offline.txt
    python -I tools/google_notices.py deps-standard.txt deps-offline.txt

The dependency files can be Gradle's tree output or plain group:artifact:version lines. Only the
standard library is used.
"""
import argparse
import json
import re
import zipfile
from pathlib import Path

TREE_LINE = re.compile(
    r"^[\s|+\\-]*([\w.\-]+):([\w.\-]+)(?::([\w.\-]+))?(?: -> ([\w.\-]+))?(?: \((?:\*|c|n)\))?\s*$"
)
APACHE_POINTER = (
    "[Apache License, Version 2.0: the full text is in THIRD_PARTY_NOTICES.md, "
    "section \"Apache License 2.0\".]"
)
APACHE_APPENDIX = re.compile(
    r"\s*APPENDIX: How to apply the Apache License to your work\..*?"
    r"Copyright \[yyyy\] \[name of copyright owner\].*?limitations under the License\.",
    re.S,
)
RULE = "-" * 80


def read_deps(path):
    """Returns the resolved group:artifact:version set from a Gradle tree or a plain list."""
    deps = set()
    for line in Path(path).read_text(encoding="utf-8", errors="replace").splitlines():
        if line.rstrip().endswith(("(c)", "(n)")):
            continue  # constraints and unresolved entries are not on the classpath
        m = TREE_LINE.match(line)
        if m and (m.group(3) or m.group(4)):
            group, artifact, declared, resolved = m.groups()
            if "." in group:  # skips "project :core" and task headings
                deps.add(f"{group}:{artifact}:{resolved or declared}")
    return deps


def apache_body_pattern(apache_text):
    """Matches a copy of the license terms, with or without the closing END OF TERMS line."""
    start = apache_text.index("Apache License")
    end = apache_text.index("additional liability.") + len("additional liability.")
    words = re.findall(r"\S+", apache_text[start:end])
    body = r"\s+".join(re.escape(w) for w in words).replace("http://", "https?://")
    return re.compile(r"[ \t]*" + body + r"(?:\s+END OF TERMS AND CONDITIONS)?")


def find_artifact(cache, coordinate):
    group, artifact, version = coordinate.split(":")
    for ext in ("aar", "jar"):
        hits = sorted((cache / group / artifact / version).glob(f"*/{artifact}-{version}.{ext}"))
        if hits:
            return hits[0]
    return None


def notices_in(path):
    """Yields (component, text) pairs from an AAR's third_party_licenses files."""
    with zipfile.ZipFile(path) as z:
        names = set(z.namelist())
        if "third_party_licenses.json" not in names or "third_party_licenses.txt" not in names:
            return
        index = json.loads(z.read("third_party_licenses.json"))
        raw = z.read("third_party_licenses.txt")
    for component, span in index.items():
        text = raw[span["start"]:span["start"] + span["length"]].decode("utf-8", "replace")
        yield component, text


def clean(text, apache):
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = apache.sub(APACHE_POINTER, text)
    text = APACHE_APPENDIX.sub("", text)
    lines = [line.rstrip() for line in text.strip("\n").split("\n")]
    return "\n".join(lines).strip("\n")


def line_set(text):
    return {line.strip() for line in text.split("\n") if line.strip()}


def normalized(text):
    return re.sub(r"\s+", " ", text).strip()


def collect(cache, coordinates, apache):
    """Returns ({component: [texts]}, [libraries with notices])."""
    found, libraries, canonical = {}, [], {}
    for coordinate in sorted(coordinates):
        path = find_artifact(cache, coordinate)
        if path is None:
            continue
        pairs = list(notices_in(path))
        if pairs:
            libraries.append(coordinate)
        for component, text in pairs:
            # Texts that differ only in whitespace are the same notice.
            text = clean(text, apache)
            text = canonical.setdefault(normalized(text), text)
            variants = found.setdefault(component, [])
            if text not in variants:
                variants.append(text)
    # Drop a variant whose lines all appear in a longer variant of the same component.
    for component, variants in found.items():
        variants.sort(key=len, reverse=True)
        kept = []
        for text in variants:
            if not any(line_set(text) <= line_set(k) for k in kept):
                kept.append(text)
        found[component] = kept
    return found, libraries


def render(title, scope, libraries, components):
    by_text = {}
    for component in sorted(components, key=str.lower):
        for text in components[component]:
            by_text.setdefault(text, []).append(component)
    blocks = sorted(by_text.items(), key=lambda item: item[1][0].lower())
    out = [
        title,
        "=" * len(title),
        "",
        scope,
        "",
        "Google LLC ships these notices inside its libraries (third_party_licenses.txt in each",
        "library). They are reproduced here unchanged, except that identical texts appear once, a",
        "notice fully contained in a longer version of the same component's notice is left out, and",
        "every copy of the Apache License 2.0 is replaced by a pointer to the full text in",
        "THIRD_PARTY_NOTICES.md. Generated by tools/google_notices.py.",
        "",
        "Libraries:",
        *[f"  {c}" for c in libraries],
        "",
        f"Components ({len(components)}):",
        *[f"  {c}" for c in sorted(components, key=str.lower)],
    ]
    for text, names in blocks:
        out += ["", "", RULE, "\n".join(names), RULE, "", text]
    return "\n".join(out) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("standard_deps")
    parser.add_argument("offline_deps")
    parser.add_argument("--cache", type=Path,
                        default=Path.home() / ".gradle/caches/modules-2/files-2.1")
    parser.add_argument("--apache", type=Path, default=None,
                        help="Apache-2.0 text used to recognize copies (default: from THIRD_PARTY_NOTICES.md)")
    parser.add_argument("--out", type=Path, default=Path(__file__).resolve().parent.parent / "third_party")
    args = parser.parse_args()

    if args.apache:
        apache_text = args.apache.read_text(encoding="utf-8")
    else:
        notices = (Path(__file__).resolve().parent.parent / "THIRD_PARTY_NOTICES.md").read_text(encoding="utf-8")
        apache_text = notices[notices.index("                                 Apache License"):]
    apache = apache_body_pattern(apache_text)

    standard, offline = read_deps(args.standard_deps), read_deps(args.offline_deps)
    std_found, std_libs = collect(args.cache, standard, apache)
    off_found, off_libs = collect(args.cache, offline - standard, apache)
    # Components the offline-only libraries share with the standard edition are already covered.
    off_only = {}
    for component, texts in off_found.items():
        known = std_found.get(component, [])
        rest = [t for t in texts if not any(line_set(t) <= line_set(k) for k in known)]
        if rest:
            off_only[component] = rest

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "google-notices.txt").write_text(render(
        "Open-source notices in Google libraries used by Usugami",
        "These libraries are part of both editions of Usugami (Usugami and Usugami Offline).",
        std_libs, std_found), encoding="utf-8", newline="\n")
    (args.out / "google-notices-offline.txt").write_text(render(
        "Open-source notices in Google libraries used only by Usugami Offline",
        "These libraries are only part of Usugami Offline (the edition with built-in text\n"
        "recognition). The notices in google-notices.txt apply to it as well.",
        off_libs, off_only), encoding="utf-8", newline="\n")
    print(f"{len(std_found)} components from {len(std_libs)} libraries; "
          f"{len(off_only)} more only in the offline edition ({len(off_libs)} libraries)")


if __name__ == "__main__":
    main()
