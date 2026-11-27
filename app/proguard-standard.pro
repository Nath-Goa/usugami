# Standard edition only (Google ML Kit).

# ML Kit finds its parts through Firebase's component registrars, which it creates by class name
# with their no-argument constructor. The libraries' own rule keeps the registrar classes but not
# that constructor, and R8's full mode removes it; ML Kit then has no text recognizer and
# TextRecognition.getClient fails. Keep the constructors (a few hundred bytes).
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }

# The native code of ML Kit's translation and language identification calls Java by name
# (TranslateJni's newLoadingException and newTranslateException, IdentifiedLanguage's
# constructor), which no Java code calls. They need no rule here: they are annotated
# @UsedByNative, which ML Kit's own rule keeps. .github/scripts/check-mlkit-registrars.sh checks it.

# Google's libraries write to Android's system log at the verbose, debug and info levels, which
# anyone with adb or a bug report can read: ML Kit's Java code logs the installation id it makes
# for Firebase and, once registered, its tokens. Leave those calls out. Warnings and errors stay
# (they hold no text, ids or tokens). What ML Kit's native code logs stays too, as R8 can't reach
# it: the text being translated and its translation, at the debug and info levels.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
