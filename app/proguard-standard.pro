# Standard edition only (Google ML Kit).

# ML Kit finds its parts through Firebase's component registrars, which it creates by class name
# with their no-argument constructor. The libraries' own rule keeps the registrar classes but not
# that constructor, and R8's full mode removes it; ML Kit then has no text recognizer and
# TextRecognition.getClient fails. Keep the constructors (a few hundred bytes).
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }

# The native code of ML Kit's translation and language identification calls Java by name: the
# translation's makes its errors with TranslateJni's newLoadingException and newTranslateException,
# the language identification's makes its results with IdentifiedLanguage's constructor. No Java
# code calls them, so R8 would remove them, and a translation that fails, or any language
# detection, would then crash, in release builds only.
-keepclassmembers class com.google.mlkit.nl.translate.internal.TranslateJni {
    static java.lang.Exception newLoadingException(int);
    static java.lang.Exception newTranslateException(int);
}
-keep class com.google.mlkit.nl.languageid.IdentifiedLanguage { <init>(java.lang.String, float); }
