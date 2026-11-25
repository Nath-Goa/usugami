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
