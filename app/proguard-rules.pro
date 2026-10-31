# PDFBox's optional JPEG 2000 codec is not bundled. Such images remain unchanged
# during compression; split and merge preserve their PDF objects directly.
-dontwarn com.gemalto.jp2.**

# BouncyCastle is excluded in build.gradle.kts. PDFBox only needs it for
# certificate-encrypted PDFs, which Usugami reports as unsupported.
-dontwarn org.bouncycastle.**

# ML Kit finds its parts through Firebase's component registrars, which it creates by class name
# with their no-argument constructor. The libraries' own rule keeps the registrar classes but not
# that constructor, and R8's full mode removes it; ML Kit then has no text recognizer and
# TextRecognition.getClient fails. Keep the constructors (a few hundred bytes).
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
