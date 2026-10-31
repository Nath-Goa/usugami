# Security policy

## Supported versions

Only the latest release of Usugami gets security fixes. Both editions (Usugami and Usugami Offline) are
covered. Please update before reporting: https://github.com/Nath-Goa/usugami/releases/latest

## Reporting a vulnerability

Please report security problems privately, not in a public issue:

1. Open the repository's [Security tab](https://github.com/Nath-Goa/usugami/security).
2. Click **Report a vulnerability**, describe the problem and how to reproduce it, and attach a
   sample file if one is needed.

You'll get a reply as soon as possible. Usugami is maintained by one person, so please allow a few
days. Fixes ship in a new release, and you'll be credited in the advisory unless you'd rather not
be.

## Scope

In scope:

- The Usugami app (both editions) and its source code in this repository: for example, a crafted
  image or PDF that makes Usugami crash in a way another app can exploit, read files it shouldn't,
  or leak data from one file into another.
- Anything that lets another app on the phone read Usugami's private files or the files you work on.
- The release process: the GitHub Actions workflows, signing and published APKs.

Out of scope (please report these to their own projects):

- Bugs in Google ML Kit, Google Play services, Android itself or the PDF viewer you open files
  with.
- Problems that need a rooted or already compromised phone.
- Copies of Usugami from anywhere other than this repository's releases. Check the signing
  certificate (see the README) to make sure you have the real app.
