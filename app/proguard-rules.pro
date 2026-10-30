# PDFBox's optional JPEG 2000 codec is not bundled. Such images remain unchanged
# during compression; split and merge preserve their PDF objects directly.
-dontwarn com.gemalto.jp2.**

# BouncyCastle is excluded in build.gradle.kts. PDFBox only needs it for
# certificate-encrypted PDFs, which PDFer reports as unsupported.
-dontwarn org.bouncycastle.**

# ML Kit's libraries ship their own keep rules for the classes they load by name.
