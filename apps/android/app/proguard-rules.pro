# kotlinx.serialization keeps generated serializers via its own consumer rules.
# Keep protocol models' names stable for readable crash reports.
-keepnames class io.github.aspershupadhyay.latch.protocol.** { *; }
# ADR-056, ADR-057: started by the ADB shell with app_process, never referenced in the app.
-keep class io.github.aspershupadhyay.latch.session.ScreenLight { public static void main(java.lang.String[]); }
-keep class io.github.aspershupadhyay.latch.session.ShellInput { public static void main(java.lang.String[]); }
