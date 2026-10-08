mkdir -p /home/claude/VideoShrink/app/src/main/java/com/example/videoshrink /home/claude/VideoShrink/.github/workflows && cd /home/claude/VideoShrink

cat > settings.gradle.kts <<'EOF'
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "VideoShrink"
include(":app")
EOF

cat > build.gradle.kts <<'EOF'
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
EOF

cat > gradle.properties <<'EOF'
org.gradle.jvmargs=-Xmx3g
android.useAndroidX=true
kotlin.code.style=official
EOF

cat > .gitignore <<'EOF'
.gradle/
build/
local.properties
.idea/
*.iml
EOF

cat > app/build.gradle.kts <<'EOF'
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.videoshrink"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.videoshrink"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
}
EOF

cat > app/src/main/AndroidManifest.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="VideoShrink"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
EOF

cat > app/src/main/java/com/example/videoshrink/MainActivity.kt <<'EOF'
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.videoshrink

import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import java.io.File

class MainActivity : ComponentActivity() {

    private data class Quality(val label: String, val height: Int, val bitrate: Int)

    private val qualities = listOf(
        Quality("High  (1080p, ~4 Mbps)", 1080, 4_000_000),
        Quality("Medium  (720p, ~2 Mbps)  - recommended", 720, 2_000_000),
        Quality("Low  (480p, ~1 Mbps)", 480, 1_000_000)
    )

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pickBtn: Button
    private lateinit var group: RadioGroup

    private var queue = ArrayDeque<Uri>()
    private var total = 0
    private var done = 0
    private var originalBytes = 0L
    private var newBytes = 0L
    private var selected = qualities[1]

    private val picker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) start(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply { text = "VideoShrink"; textSize = 28f })
        root.addView(TextView(this).apply {
            text = "Pick videos, choose a quality, and get smaller copies in Movies/Compressed. Originals are never touched."
            setPadding(0, pad / 2, 0, pad)
        })

        group = RadioGroup(this)
        qualities.forEachIndexed { i, q ->
            group.addView(RadioButton(this).apply { id = i; text = q.label })
        }
        group.check(1)
        group.setOnCheckedChangeListener { _, id -> selected = qualities[id] }
        root.addView(group)

        pickBtn = Button(this).apply { text = "Choose videos"; setOnClickListener { picker.launch("video/*") } }
        root.addView(pickBtn)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            isIndeterminate = false
        }
        root.addView(progress)

        status = TextView(this).apply { setPadding(0, pad, 0, 0); gravity = Gravity.START }
        root.addView(ScrollView(this).apply { addView(status) })
        setContentView(root)
    }

    private fun start(uris: List<Uri>) {
        queue = ArrayDeque(uris)
        total = uris.size
        done = 0
        originalBytes = 0
        newBytes = 0
        pickBtn.isEnabled = false
        group.isEnabled = false
        for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = false
        progress.max = total
        progress.progress = 0
        progress.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = ""
        next()
    }

    private fun next() {
        val uri = queue.removeFirstOrNull()
        if (uri == null) { finish(); return }
        val name = displayName(uri)
        val srcSize = size(uri)
        status.append("(${done + 1}/$total) Compressing $name ...\n")

        val tmp = File(cacheDir, "out_${System.nanoTime()}.mp4")
        val effects = mutableListOf<androidx.media3.common.Effect>()
        if (sourceHeight(uri) > selected.height) effects.add(Presentation.createForHeight(selected.height))

        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setEffects(Effects(emptyList(), effects))
            .build()

        val encoder = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(selected.bitrate).build()
            ).build()

        val transformer = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .setEncoderFactory(encoder)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    val outSize = tmp.length()
                    if (outSize in 1 until srcSize || srcSize == 0L) {
                        saveToGallery(tmp, name)
                        originalBytes += srcSize
                        newBytes += outSize
                        status.append("   ${fmt(srcSize)} -> ${fmt(outSize)}\n")
                    } else {
                        status.append("   Skipped (already small, result would be bigger)\n")
                    }
                    tmp.delete()
                    done++
                    progress.progress = done
                    next()
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    tmp.delete()
                    status.append("   Failed: ${exportException.message}\n")
                    done++
                    progress.progress = done
                    next()
                }
            }).build()

        transformer.start(item, tmp.absolutePath)
    }

    private fun finish() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pickBtn.isEnabled = true
        for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = true
        status.append("\nDone. Saved ${fmt(originalBytes - newBytes)}.\nCompressed copies are in Movies/Compressed. " +
            "Check them, then delete the originals from your gallery to free the space.\n")
    }

    private fun saveToGallery(file: File, name: String) {
        val base = name.substringBeforeLast('.')
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${base}_small.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Compressed")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val target = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
        contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        contentResolver.update(target, values, null, null)
    }

    private fun sourceHeight(uri: Uri): Int = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(this, uri)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
        r.release()
        if (rot == 90 || rot == 270) w else h
    } catch (e: Exception) { 0 }

    private fun displayName(uri: Uri): String = query(uri, OpenableColumns.DISPLAY_NAME) ?: "video.mp4"
    private fun size(uri: Uri): Long = query(uri, OpenableColumns.SIZE)?.toLongOrNull() ?: 0L
    private fun query(uri: Uri, col: String): String? =
        contentResolver.query(uri, arrayOf(col), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    private fun fmt(b: Long) = Formatter.formatFileSize(this, b)
}
EOF

cat > .github/workflows/build.yml <<'EOF'
name: Build APK
on:
  push:
  workflow_dispatch:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: 8.7
      - run: gradle assembleDebug
      - uses: actions/upload-artifact@v4
        with:
          name: VideoShrink-apk
          path: app/build/outputs/apk/debug/app-debug.apk
EOF

cd /home/claude && mkdir -p /mnt/user-data/outputs && zip -rq /mnt/user-data/outputs/VideoShrink.zip VideoShrink && ls -la /mnt/user-data/outputs
Output

total 12
drwxr-xr-x 1 claude ubuntu    0 Oct  8 17:34 .
drwxr-xr-x 5 root   root   4096 Oct  8 17:34 ..
-rw-r--r-- 1 claude ubuntu 7444 Oct  8 17:34 VideoShrink.zip
