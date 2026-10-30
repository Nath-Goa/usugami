# PDFer license notice and additional permission

PDFer
Copyright (C) 2026 Nathan Kumtakar

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with this program (the
file LICENSE). If not, see <https://www.gnu.org/licenses/>.

## Additional permission under GNU GPL version 3 section 7

If you modify this Program, or any covered work, by linking or combining it with Google ML Kit
(the libraries published by Google LLC under the Maven group IDs "com.google.mlkit" and
"com.google.android.odml", and the "com.google.android.gms:play-services-mlkit-*" libraries,
together with the machine-learning models and modules they load at run time), with the Google Play
services client libraries (the libraries published by Google LLC under the Maven group ID
"com.google.android.gms"), or with any library that Google LLC distributes as a dependency of those
libraries (or a modified version of any of those libraries), containing parts covered by the terms
of the ML Kit Terms of Service, the Google APIs Terms of Service, the Android Software Development
Kit License Agreement, or other terms under which Google LLC distributes those libraries, the
licensors of this Program grant you additional permission to convey the resulting work.

## Why this permission exists

PDFer's text recognition (OCR) uses Google ML Kit, which is not open source. Every PDFer APK
contains ML Kit and Google Play services client code, and the GPL alone would not let other people
share such an APK, or a modified version of it. This permission makes that possible. It covers
only those Google libraries; every other part of PDFer stays under the GPL. As section 7 of the
GPL allows, anyone who passes PDFer on may remove this permission from their copy (for example, a
fork that drops ML Kit).
