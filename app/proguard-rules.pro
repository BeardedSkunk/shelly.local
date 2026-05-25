-keep class com.pearlnode.model.** { *; }
-keepattributes *Annotation*

# Room TypeConverter methods are called reflectively by the generated DAO code
-keep class com.pearlnode.data.db.Converters { *; }
