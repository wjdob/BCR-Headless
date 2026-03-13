# Keep only the pieces that are loaded reflectively or invoked from the
# module shell scripts. Everything else can still be optimized normally.

# Keep log tags.
-keepclasseswithmembers,allowoptimization,allowshrinking class com.chiller3.bcr.** {
    static final java.lang.String TAG;
}
-keep,allowoptimization,allowshrinking class com.chiller3.bcr.RecorderThread {
}

# We construct TreeDocumentFile via reflection in DocumentFileExtensions
# to speed up SAF performance when doing path lookups.
-keepclassmembers class androidx.documentfile.provider.SingleDocumentFile {
    private android.content.Context mContext;
}
-keepclassmembers class androidx.documentfile.provider.TreeDocumentFile {
    <init>(androidx.documentfile.provider.DocumentFile, android.content.Context, android.net.Uri);

    private android.content.Context mContext;
}

# ChipGroupCentered accesses this via reflection.
-keepclassmembers class com.google.android.material.internal.FlowLayout {
    private int rowCount;
}

# Keep the headless daemon entrypoint. It is launched directly from the module
# scripts with app_process, so the fully qualified main() name must remain
# stable even though the helper APK is otherwise aggressively stripped.
-keep class com.chiller3.bcr.headless.HeadlessMain {
    void main(java.lang.String[]);
}
