# Skip uses Swift type metadata and reflection. Preserve names and members while
# allowing R8 to optimize method bodies in the optimized installable build.
-keep,allowoptimization class skip.** { *; }
-keep,allowoptimization class com.dynamicdroid.islandmorphui.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
