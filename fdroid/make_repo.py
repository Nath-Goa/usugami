"""Sets up the folder fdroidserver needs to make Usugami's own F-Droid repository.

Run by .github/workflows/fdroid-repo.yml; then `fdroid update` in the output folder signs the index.
The apps are the release APKs, as published on GitHub (signed by the developer); nothing is built.

    python make_repo.py SOURCE OUT APK...

SOURCE is a checkout of this repository, OUT an empty folder, and each APK a release APK whose
package is one of APPS. Writes OUT/config.yml (the signing secrets come from the environment),
OUT/metadata/<app>.yml with each app's localized store texts and images, OUT/repo/ with the APKs and
OUT/repo/icons/icon.png.
"""
import base64
import os
import shutil
import sys
from pathlib import Path

from ruamel.yaml import YAML

REPO_URL = "https://nathank.me/usugami/fdroid/repo"

# Each app: its recipe (for the store fields) and its store texts and images, later folders on top.
APPS = {
    "io.github.nath_goa.usugami": (".fdroid.yml", ["fastlane/metadata/android"]),
    "io.github.nath_goa.usugami.lite": (
        "fdroid/io.github.nath_goa.usugami.lite.yml",
        ["fastlane/metadata/android", "src/lite/fastlane/metadata/android"],
    ),
}

# The store fields of the recipes; how F-Droid builds the app doesn't apply to published APKs.
KEEP = ["Categories", "License", "AuthorName", "AuthorEmail", "WebSite", "SourceCode",
        "IssueTracker", "Changelog", "Donate", "AutoName", "AllowedAPKSigningKeys"]


def main(source: Path, out: Path, apks: list[Path]) -> None:
    yaml = YAML()
    (out / "metadata").mkdir(parents=True)
    (out / "repo" / "icons").mkdir(parents=True)
    for appid, (recipe, store) in APPS.items():
        with open(source / recipe, encoding="utf-8") as f:
            full = yaml.load(f)
        # AllowedAPKSigningKeys stays: fdroid update then refuses an APK not signed by the developer.
        meta = {k: full[k] for k in KEEP if k in full}
        with open(out / "metadata" / f"{appid}.yml", "w", encoding="utf-8") as f:
            yaml.dump(meta, f)
        for folder in store:
            src = source / folder
            if src.is_dir():
                shutil.copytree(src, out / "metadata" / appid, dirs_exist_ok=True)
    for apk in apks:
        shutil.copy2(apk, out / "repo" / apk.name)
    shutil.copy2(source / "fastlane/metadata/android/en-US/images/icon.png", out / "repo/icons/icon.png")

    keystore = out / "keystore.p12"
    keystore.write_bytes(base64.b64decode(os.environ["REPO_KEYSTORE_BASE64"]))
    password = os.environ["REPO_KEYSTORE_PASSWORD"]
    config = {
        "repo_url": REPO_URL,
        "repo_name": "Usugami",
        "repo_description": (
            "Usugami and Usugami Lite: offline, ad-free PDF tools for Android, from their developer. "
            "The same APKs, signed with the same key, as the GitHub releases at "
            "https://github.com/Nath-Goa/usugami"
        ),
        "repo_icon": "icon.png",
        "archive_older": 0,
        "make_current_version_link": False,
        "sdk_path": os.environ["ANDROID_HOME"],
        "keystore": str(keystore),
        "repo_keyalias": "usugami-fdroid-repo",
        "keystorepass": password,
        "keypass": password,
        "keydname": "CN=Usugami F-Droid repository, O=Usugami",
    }
    config_path = out / "config.yml"
    with open(config_path, "w", encoding="utf-8") as f:
        yaml.dump(config, f)
    os.chmod(config_path, 0o600)
    os.chmod(keystore, 0o600)


if __name__ == "__main__":
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    main(Path(sys.argv[1]), Path(sys.argv[2]), [Path(a) for a in sys.argv[3:]])
