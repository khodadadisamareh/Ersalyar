# ارسال‌یار release hardening.
# Keep model fields for Gson serialization, but DO NOT keep whole classes/methods:
# R8 should obfuscate the implementation and make casual source recovery harder.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations,AnnotationDefault,Signature
-keepclassmembers class ir.ersalyar.app.** {
    <fields>;
}
-keep @interface com.google.gson.annotations.SerializedName
-dontwarn javax.annotation.**
