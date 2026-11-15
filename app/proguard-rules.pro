# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class de.doorzign.app.** { *** Companion; }
-keepclasseswithmembers class de.doorzign.app.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class de.doorzign.app.**$$serializer { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
