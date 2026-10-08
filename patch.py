#!/usr/bin/env python3
"""Patches the freshly generated Capacitor android/ project: copies the native Java files,
adds permissions + foreground service to the manifest and raises minSdk to 29 (MediaStore RELATIVE_PATH)."""
import os, re, shutil, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AND = os.path.join(ROOT, "android")
PKG_DIR = os.path.join(AND, "app", "src", "main", "java", "xyz", "juicevault", "studio")
if not os.path.isdir(PKG_DIR):
    sys.exit("android/ project not found - run `npx cap add android` first")

for f in ("JuiceVaultPlugin.java", "KeepAliveService.java", "MainActivity.java"):
    shutil.copy(os.path.join(ROOT, "native", f), os.path.join(PKG_DIR, f))

mf = os.path.join(AND, "app", "src", "main", "AndroidManifest.xml")
s = open(mf, encoding="utf-8").read()
if "KeepAliveService" not in s:
    s = s.replace("</application>",
                  '    <service android:name=".KeepAliveService" android:exported="false"\n'
                  '        android:foregroundServiceType="dataSync" />\n    </application>')
    perms = (
        '    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />\n'
        '    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />\n'
        '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />\n'
        '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />\n'
        '    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\n')
    s = s.replace("</manifest>", perms + "</manifest>")
    open(mf, "w", encoding="utf-8").write(s)

vg = os.path.join(AND, "variables.gradle")
v = open(vg, encoding="utf-8").read()
v = re.sub(r"minSdkVersion\s*=\s*\d+", "minSdkVersion = 29", v)
open(vg, "w", encoding="utf-8").write(v)
print("patched OK")
