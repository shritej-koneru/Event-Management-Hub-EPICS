# Add project specific ProGuard rules here.

-keep class com.example.eventmanagementhub.data.model.** { *; }

# osmdroid (OpenStreetMap map view) — keep the public API surface intact.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**