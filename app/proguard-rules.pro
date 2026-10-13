# MotionWake ProGuard rules
-keep class com.egraf.motionwake.** { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep <methods>;
}
-keepattributes InnerClasses,EnclosingMethod,*Annotation*
