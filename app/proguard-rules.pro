# Keep rules for release builds (R8).

# Kotlin's standard library splits big files like Strings.kt into a chain of package-private
# classes (StringsKt__IndentKt, StringsKt__StringBuilderKt, ...) that StringsKt extends.
# R8 was moving some of them to another package and renaming them, while leaving their parent
# behind, so the moved class couldn't reach it and the app crashed on start with
# "IllegalAccessError: Class kotlin.text.h extended by class ym1 is inaccessible".
# Keeping their names keeps the chain together in its package; they can still be shrunk and optimized.
-keepnames,allowoptimization class kotlin.**.*Kt__*
