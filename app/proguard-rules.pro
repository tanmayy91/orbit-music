# Orbit Music keeps no reflection of its own; the libraries bring their own rules.
# Media3's session and the playback service are reached by name from the system.
-keep class app.orbitmusic.player.PlaybackService { *; }
-keep class app.orbitmusic.player.KeepService { *; }
# kotlinx.serialization: the generated serializers of the catalogue's models.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.orbitmusic.** { *** Companion; }
-keepclasseswithmembers class app.orbitmusic.** { kotlinx.serialization.KSerializer serializer(...); }
