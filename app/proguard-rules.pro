-keep class com.pearlnode.model.** { *; }
-keepattributes *Annotation*

# Room TypeConverter methods are called reflectively by the generated DAO code
-keep class com.pearlnode.data.db.Converters { *; }

# Tink (pulled in by androidx.security.crypto) references error-prone annotations
# that are compile-time only and not present in the runtime classpath
-dontwarn com.google.errorprone.annotations.**
