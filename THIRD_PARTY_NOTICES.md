# Third-party notices

Usugami is free software, licensed under the GNU General Public License version 3 or later, with an
additional permission for Google's ML Kit and Google Play services libraries. See LICENSE and
LICENSE-EXCEPTION.md.

Usugami is built with the software, fonts and data files listed here, made by other people. This
file gives their versions, licenses and copyright notices, and the license texts they require.
It applies to Usugami 1.2.0 and covers both editions:

- "Both editions": Usugami (the standard edition) and Usugami Offline.
- "Usugami Offline only": only in Usugami Offline, which has text recognition built in.

Each full license text appears once, in section 6 or next to the component that needs it.

Contents

1. Libraries (open source)
2. Apache PDFBox for Android, with its notices
3. Fonts
4. Google ML Kit and Google Play services (not open source)
5. Open-source code inside Google's libraries
6. License texts


## 1. Libraries (open source)

All libraries in this section are in both editions and are licensed under the Apache License,
Version 2.0 (full text in section 6).

### AndroidX and Jetpack Compose

Copyright The Android Open Source Project.

- androidx.activity: activity, activity-compose, activity-ktx 1.13.0
- androidx.annotation: annotation, annotation-jvm 1.9.1; annotation-experimental 1.4.1
- androidx.appcompat: appcompat, appcompat-resources 1.6.1
- androidx.arch.core: core-common, core-runtime 2.2.0
- androidx.autofill: autofill 1.0.0
- androidx.collection: collection, collection-jvm, collection-ktx 1.5.0
- androidx.compose.animation: animation, animation-android, animation-core, animation-core-android
  1.10.6
- androidx.compose.foundation: foundation, foundation-android, foundation-layout,
  foundation-layout-android 1.10.6
- androidx.compose.material3: material3, material3-android 1.4.0
- androidx.compose.material: material-icons-core, material-icons-core-android,
  material-icons-extended, material-icons-extended-android 1.7.8; material-ripple,
  material-ripple-android 1.8.1
- androidx.compose.runtime: runtime, runtime-android, runtime-annotation,
  runtime-annotation-android, runtime-retain, runtime-retain-android, runtime-saveable,
  runtime-saveable-android 1.10.6
- androidx.compose.ui: ui, ui-android, ui-geometry, ui-geometry-android, ui-graphics,
  ui-graphics-android, ui-text, ui-text-android, ui-unit, ui-unit-android, ui-util,
  ui-util-android 1.10.6
- androidx.concurrent: concurrent-futures 1.1.0
- androidx.core: core, core-ktx 1.18.0; core-viewtree 1.0.0
- androidx.cursoradapter: cursoradapter 1.0.0
- androidx.customview: customview, customview-poolingcontainer 1.0.0
- androidx.documentfile: documentfile 1.0.0
- androidx.drawerlayout: drawerlayout 1.0.0
- androidx.dynamicanimation: dynamicanimation 1.0.0
- androidx.emoji2: emoji2, emoji2-views-helper 1.4.0
- androidx.exifinterface: exifinterface 1.4.2
- androidx.fragment: fragment 1.3.6
- androidx.graphics: graphics-path 1.0.1
- androidx.interpolator: interpolator 1.0.0
- androidx.legacy: legacy-support-core-utils 1.0.0
- androidx.lifecycle: lifecycle-common, lifecycle-common-java8, lifecycle-common-jvm,
  lifecycle-livedata, lifecycle-livedata-core, lifecycle-livedata-core-ktx, lifecycle-process,
  lifecycle-runtime, lifecycle-runtime-android, lifecycle-runtime-compose,
  lifecycle-runtime-compose-android, lifecycle-runtime-ktx, lifecycle-runtime-ktx-android,
  lifecycle-viewmodel, lifecycle-viewmodel-android, lifecycle-viewmodel-compose,
  lifecycle-viewmodel-compose-android, lifecycle-viewmodel-ktx, lifecycle-viewmodel-savedstate,
  lifecycle-viewmodel-savedstate-android 2.10.0
- androidx.loader: loader 1.0.0
- androidx.localbroadcastmanager: localbroadcastmanager 1.0.0
- androidx.navigationevent: navigationevent, navigationevent-android, navigationevent-compose,
  navigationevent-compose-android 1.0.0
- androidx.print: print 1.0.0
- androidx.profileinstaller: profileinstaller 1.4.0
- androidx.resourceinspection: resourceinspection-annotation 1.0.1
- androidx.savedstate: savedstate, savedstate-android, savedstate-compose,
  savedstate-compose-android, savedstate-ktx 1.4.0
- androidx.startup: startup-runtime 1.1.1
- androidx.tracing: tracing 1.2.0
- androidx.transition: transition 1.6.0
- androidx.vectordrawable: vectordrawable, vectordrawable-animated 1.1.0
- androidx.versionedparcelable: versionedparcelable 1.1.1
- androidx.viewpager: viewpager 1.0.0
- androidx.window: window, window-core, window-core-android 1.5.0

Some of these (for example appcompat, fragment and the legacy libraries) are included only
because Google ML Kit depends on them. Usugami itself doesn't use them, and the build's code
shrinker removes the parts that are never used.

### Compose Multiplatform metadata

Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.

- org.jetbrains.compose.animation: animation, animation-core 1.7.0
- org.jetbrains.compose.annotation-internal: annotation 1.7.0
- org.jetbrains.compose.collection-internal: collection 1.7.0
- org.jetbrains.compose.foundation: foundation, foundation-layout 1.7.0
- org.jetbrains.compose.runtime: runtime, runtime-saveable 1.9.2
- org.jetbrains.compose.ui: ui, ui-geometry, ui-graphics, ui-text, ui-unit, ui-util 1.7.0
- org.jetbrains.androidx.lifecycle: lifecycle-common, lifecycle-runtime, lifecycle-runtime-compose,
  lifecycle-viewmodel 2.9.5
- org.jetbrains.androidx.savedstate: savedstate, savedstate-compose 1.3.5

These come with the Reorderable library. On Android they only point to the AndroidX libraries
above and add no code of their own.

### Kotlin and kotlinx

- Kotlin standard library (org.jetbrains.kotlin: kotlin-stdlib, kotlin-stdlib-common 2.2.21;
  kotlin-stdlib-jdk7, kotlin-stdlib-jdk8 1.9.0) and kotlin-parcelize-runtime 2.2.21.
  Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
- kotlinx.coroutines (org.jetbrains.kotlinx: kotlinx-coroutines-core, kotlinx-coroutines-core-jvm,
  kotlinx-coroutines-android 1.10.2).
  Copyright 2000-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
- kotlinx.serialization (org.jetbrains.kotlinx: kotlinx-serialization-core,
  kotlinx-serialization-core-jvm 1.7.3). Copyright 2017-2022 JetBrains s.r.o.
- kotlinx.atomicfu (org.jetbrains.kotlinx: atomicfu, atomicfu-jvm 0.23.2).
  Copyright 2017 JetBrains s.r.o.
- JetBrains Java Annotations (org.jetbrains: annotations 23.0.0). Copyright 2000-2021 JetBrains s.r.o.

### Reorderable

- sh.calvin.reorderable: reorderable, reorderable-android 3.1.0 (drag-to-reorder lists).
  Copyright 2023 Calvin Liang. https://github.com/Calvin-LL/Reorderable

### Google DataTransport and Firebase components

Copyright Google LLC. Google ML Kit uses these internally.

- com.google.android.datatransport: transport-api 2.2.1, transport-backend-cct 2.3.3,
  transport-runtime 2.2.6
- com.google.firebase: firebase-annotations 16.0.0, firebase-components 16.1.0,
  firebase-encoders 16.1.0, firebase-encoders-json 17.1.0

Usugami removes the upload backend of DataTransport from the app, and the app has no internet
permission.

### Small libraries

- javax.inject 1 (javax.inject). Copyright (C) 2009 The JSR-330 Expert Group.
- ListenableFuture 1.0 (com.google.guava: listenablefuture). Copyright (C) 2007 The Guava Authors.
- JSpecify 1.0.0 (org.jspecify: jspecify). Copyright 2022 The JSpecify Authors.


## 2. Apache PDFBox for Android, with its notices

- com.tom-roush: pdfbox-android 2.0.27.0, in both editions. PdfBox-Android by Tom Roush and
  contributors, a port of Apache PDFBox to Android: https://github.com/TomRoush/PdfBox-Android
- License: Apache License, Version 2.0 (full text in section 6), plus the terms below for the
  components it includes.

Usugami ships these PDFBox data files: the 14 Adobe Font Metrics (AFM) files for the standard PDF
fonts, the Identity-H and Identity-V CMaps, the Adobe Glyph List, the ITC Zapf Dingbats Glyph
List, PDFBox's additional glyph list, the Unicode file BidiMirroring.txt, and the font
Liberation Sans (see section 3). The other CMaps and Scripts.txt are left out. BouncyCastle, an
optional dependency of PdfBox-Android, is not included.

### NOTICE file of PdfBox-Android

```text
Apache PDFBox
Copyright 2014 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).

Based on source code originally developed in the PDFBox and 
FontBox projects.

Copyright (c) 2002-2007, www.pdfbox.org

Based on source code originally developed in the PaDaF project.
Copyright (c) 2010 Atos Worldline SAS

Includes the Adobe Glyph List
Copyright 1997, 1998, 2002, 2007, 2010 Adobe Systems Incorporated.

Includes the Zapf Dingbats Glyph List
Copyright 2002, 2010 Adobe Systems Incorporated.

Includes the Script Property (Scripts-10.0.0.txt)
Copyright 2017 Unicode, Inc.
```

### External components (from the LICENSE file of PdfBox-Android)

```text
EXTERNAL COMPONENTS

Apache PDFBox includes a number of components with separate copyright notices
and license terms. Your use of these components is subject to the terms and
conditions of the following licenses.

Contributions made to the original PDFBox and FontBox projects:

   Copyright (c) 2002-2007, www.pdfbox.org
   All rights reserved.

   Redistribution and use in source and binary forms, with or without
   modification, are permitted provided that the following conditions are met:

   1. Redistributions of source code must retain the above copyright notice,
      this list of conditions and the following disclaimer.

   2. Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.

   3. Neither the name of pdfbox; nor the names of its contributors may be
      used to endorse or promote products derived from this software without
      specific prior written permission.

   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
   AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
   IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
   ARE DISCLAIMED.  IN NO EVENT SHALL THE REGENTS OR CONTRIBUTORS BE LIABLE
   FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
   DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
   SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
   CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
   LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
   OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
   SUCH DAMAGE.

Adobe Font Metrics (AFM) for PDF Core 14 Fonts

   This file and the 14 PostScript(R) AFM files it accompanies may be used,
   copied, and distributed for any purpose and without charge, with or without
   modification, provided that all copyright notices are retained; that the
   AFM files are not distributed without this file; that all modifications
   to this file or any of the AFM files are prominently noted in the modified
   file(s); and that this paragraph is not modified. Adobe Systems has no
   responsibility or obligation to support the use of the AFM files. 

CMaps for PDF Fonts (http://opensource.adobe.com/wiki/display/cmap/Downloads)

   Copyright 1990-2009 Adobe Systems Incorporated.
   All rights reserved.

   Redistribution and use in source and binary forms, with or without
   modification, are permitted provided that the following conditions
   are met:

   Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.

   Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution. 

   Neither the name of Adobe Systems Incorporated nor the names of its
   contributors may be used to endorse or promote products derived from this
   software without specific prior written permission. 

   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
   AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
   IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
   ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
   LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
   CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
   SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
   INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
   CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
   ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
   THE POSSIBILITY OF SUCH DAMAGE.

PaDaF PDF/A preflight (http://sourceforge.net/projects/padaf)

  Copyright 2010 Atos Worldline SAS
 
  Licensed by Atos Worldline SAS under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  Atos Worldline SAS licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at
 
       http://www.apache.org/licenses/LICENSE-2.0
 
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
```

### Adobe Glyph List and ITC Zapf Dingbats Glyph List

The Adobe Glyph List (Copyright 1997, 1998, 2002, 2007, 2010 Adobe Systems Incorporated. All
rights reserved.) and the ITC Zapf Dingbats Glyph List (Copyright 2002, 2010 Adobe Systems
Incorporated. All rights reserved.) are distributed under the same BSD-style terms as the Adobe
CMaps quoted above. Each AFM file also keeps its own Adobe copyright and trademark notices.

### Unicode BidiMirroring.txt

BidiMirroring-8.0.0.txt, from the Unicode Character Database. Copyright (c) 1991-2015 Unicode,
Inc. For terms of use, see http://www.unicode.org/terms_of_use.html. Unicode's license for its
data files (current version):

```text
UNICODE LICENSE V3

COPYRIGHT AND PERMISSION NOTICE

Copyright © 1991-2026 Unicode, Inc.

NOTICE TO USER: Carefully read the following legal agreement. BY
DOWNLOADING, INSTALLING, COPYING OR OTHERWISE USING DATA FILES, AND/OR
SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE
TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT
DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.

Permission is hereby granted, free of charge, to any person obtaining a
copy of data files and any associated documentation (the "Data Files") or
software and any associated documentation (the "Software") to deal in the
Data Files or Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, and/or sell
copies of the Data Files or Software, and to permit persons to whom the
Data Files or Software are furnished to do so, provided that either (a)
this copyright and permission notice appear with all copies of the Data
Files or Software, or (b) this copyright and permission notice appear in
associated Documentation.

THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
THIRD PARTY RIGHTS.

IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
FILES OR SOFTWARE.

Except as contained in this notice, the name of a copyright holder shall
not be used in advertising or otherwise to promote the sale, use or other
dealings in these Data Files or Software without prior written
authorization of the copyright holder.
```


## 3. Fonts

All fonts here are licensed under the SIL Open Font License, Version 1.1 (full text in section
6).

### Usugami OCR (fonts/UsugamiOcr-Regular.ttf), both editions

An invisible font used only for the searchable text layer that text recognition adds to a PDF.
It has no visible letters (every glyph is empty) and keeps the character map and letter widths
of these fonts:

- Noto Sans 2.008. Copyright 2015-2021 Google LLC. All Rights Reserved.
- Noto Sans Devanagari 2.002. Copyright 2015-2022 Google Inc. All Rights Reserved.

Noto is a trademark of Google LLC. The license file distributed with the Noto fonts reads
"Copyright 2018 The Noto Project Authors (github.com/googlei18n/noto-fonts)". Usugami OCR is a
modified version made with tools/make_ocr_font.py from Usugami's source code. It is renamed so it
uses no Reserved Font Name, and it is distributed under the same license.

### Liberation Sans 2.1.5, both editions

Included in PdfBox-Android (used by PDFBox as a fallback font). Unmodified.

```text
Digitized data copyright (c) 2010 Google Corporation
	with Reserved Font Arimo, Tinos and Cousine.
Copyright (c) 2012 Red Hat, Inc.
	with Reserved Font Name Liberation.
```

Liberation is a trademark of Red Hat, Inc. registered in U.S. Patent and Trademark Office and
certain other jurisdictions.


## 4. Google ML Kit and Google Play services (not open source)

Usugami uses Google ML Kit for text recognition (OCR). These libraries are made by Google LLC and are
not open source. They are used under Google's terms, and Usugami's license gives an additional
permission for them (LICENSE-EXCEPTION.md). Google does not endorse Usugami.

Both editions, under the ML Kit Terms of Service (https://developers.google.com/ml-kit/terms):

- com.google.android.gms: play-services-mlkit-text-recognition 19.0.1,
  play-services-mlkit-text-recognition-devanagari 16.0.1,
  play-services-mlkit-text-recognition-common 19.1.0
- com.google.mlkit: common 18.11.0, vision-common 17.3.0, vision-interfaces 16.3.0

Both editions, under the Android Software Development Kit License
(https://developer.android.com/studio/terms):

- com.google.android.gms: play-services-base 18.5.0, play-services-basement 18.4.0,
  play-services-tasks 18.2.0
- com.google.android.odml: image 1.0.0-beta1

Usugami Offline only, under the ML Kit Terms of Service:

- com.google.mlkit: text-recognition 16.0.1, text-recognition-devanagari 16.0.1,
  text-recognition-bundled-common 17.0.0. These contain the text-recognition models and the
  native recognition library (libmlkit_google_ocr_pipeline.so).

In the standard edition, the text-recognition model is not part of Usugami. Google Play services
downloads it on first use, under Google's own terms.

Google's API terms: https://developers.google.com/terms


## 5. Open-source code inside Google's libraries

Google's libraries contain open-source code from many projects, and Google ships the license
notices for it inside each library. Usugami reproduces all of them in two files, unchanged except
that duplicate texts appear once (including the many copies of the Apache License 2.0, whose
full text is in section 6):

- third_party/google-notices.txt, for the libraries in both editions:
  https://github.com/Nath-Goa/usugami/blob/main/third_party/google-notices.txt
- third_party/google-notices-offline.txt, for the libraries only in Usugami Offline:
  https://github.com/Nath-Goa/usugami/blob/main/third_party/google-notices-offline.txt

Both files are also inside the app: About → Open-source licenses shows them after this
document (the offline file only in Usugami Offline, the edition that contains those libraries).

Components listed by Google in the libraries used by both editions:

Android SDK; Android SDK Platforms; AndroidX activity library;
AndroidX annotation experimental library; AndroidX annotation jvm library;
AndroidX annotation library; AndroidX architecture core common library;
AndroidX architecture core library; AndroidX architecture core runtime library;
AndroidX architecture library; AndroidX asynclayoutinflater library;
AndroidX collection jvm library; AndroidX collection library; AndroidX concurrent futures library;
AndroidX coordinatorlayout library; AndroidX core ktx library; AndroidX core library;
AndroidX cursoradapter library; AndroidX customview library; AndroidX documentfile library;
AndroidX drawerlayout library; AndroidX exifinterface library; AndroidX fragment library;
AndroidX interpolator library; AndroidX legacy coreui library; AndroidX legacy coreutils library;
AndroidX legacy v4 library; AndroidX lifecycle common library;
AndroidX lifecycle livedatacore library; AndroidX lifecycle runtime library;
AndroidX lifecycle viewmodel library; AndroidX lifecycle viewmodel savedstate library;
AndroidX loader library; AndroidX localbroadcastmanager library; AndroidX media base library;
AndroidX print library; AndroidX savedstate library; AndroidX swiperefreshlayout library;
AndroidX tracing library; AndroidX versionedparcelable library; AndroidX viewpager library;
Animal Sniffer; apksig; Checker Framework Annotations; Dagger; Error Prone; Firebase; Google Auto;
gsfclient; Guava JDK5; Guava JDK7; J2ObjC; Jakarta Inject; java_annotations;
JsInterop Annotations; JSpecify; JSR 250; JSR 305; JSR 330; Kotlin; Kotlin coroutines;
kotlinx_atomicfu; SafeParcelable library; TensorFlow Lite Support.

Additional components listed by Google in the libraries used only by Usugami Offline (its built-in
text-recognition engine):

Abseil; address_sorting; angle; Bazel Platforms; Boost; BoringSSL; Brotli; bzip2; c-ares; cblas;
Chrome Certificate Verifier; cnpy; cppitertools; cpu_features; cpuinfo; Crunchy Crypt; cutlass;
Darts-clone; dav1d; dawn; Dimsum; double-conversion; ducc; ecclesia; Edge TPU (0); Edge TPU (1);
Edge TPU firmware callback; Eigen 3; expat; f2reduce; fast_hash; FlatBuffers; FP16;
from_libaom (0); from_libaom (1); FXdiv; gcc_14_1_0; gemmlowp; giflib; glslang;
Google Toolbox for Mac; gRPC; gRPC Protos; Halide; highway; HighwayHash; hwloc; ICU4C; imath;
intel_dnnl; jpegxl; Khronos OpenGL headers; KleidiAI; LAPACK; legacy_libcxx_sort; Leptonica;
libaom; libavif; libdeflate; libexecinfo; libjpeg-turbo; libpfm4; liburing; libwebp; Libxml2;
libyuv; Little CMS; LLVM; mecab; MediaPipe; minizip; ml_dtypes; Mosys; mozilla_psl;
Nano Protocol Buffers; nanoflann; NCurses; neon2sse; nsync; NVIDIA Management Library; Ooura FFT;
open_platforms_util_subtle; OpenCensus; opencl_headers; opencl_icd_loader; OpenCV; openexr;
openssh; PCG Random Number Generator; pdlp; perfetto; piex; PNG; pngnq; program_image_remapper;
Proto Converter; Protocol Buffers; Protocol Buffers for Java; pthreadpool; RE2; Riegeli; ruy;
sentencepiece; sequence_lock; sha3; sjpeg; Skia; sobol_data; spirv_headers; spirv_tools;
splitfile; stablehlo; SwiftShader; tbb; TCMalloc; TensorFlow; TensorFlow Models;
TensorFlow Serving; tf_runtime; tf_runtime_eigen; tf_runtime_llvm; tiff; Tink; tz database;
Unsmear; UTF; utf8_range; vulkan_headers; vulkan_loader; vulkan_memory_allocator; vulkan_volk;
X.Org; XCB library; xmp_toolkit; XNNPACK; xxhash; Zippy; zlib; Zstandard.


## 6. License texts

### Apache License 2.0

```text
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```

### SIL Open Font License 1.1

```text
-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font
creation efforts of academic and linguistic communities, and to
provide a free and open framework in which fonts may be shared and
improved in partnership with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded,
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply to
any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software
components as distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to,
deleting, or substituting -- in part or in whole -- any of the
components of the Original Version, by changing formats or by porting
the Font Software to a new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed,
modify, redistribute, and sell modified and unmodified copies of the
Font Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components, in
Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the
corresponding Copyright Holder. This restriction only applies to the
primary font name as presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created using
the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
```
