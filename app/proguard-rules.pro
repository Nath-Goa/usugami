# PDFBox's optional JPEG 2000 codec is not bundled. Such images remain unchanged
# during compression; split and merge preserve their PDF objects directly.
-dontwarn com.gemalto.jp2.**

# ML Kit's bundled OCR registers its factories by class name. Keep these
# components so release shrinking does not remove or merge their providers.
-keep class com.google.mlkit.** { *; }
-keep class com.google.firebase.components.** { *; }
