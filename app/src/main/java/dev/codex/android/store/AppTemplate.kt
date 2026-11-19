package dev.codex.android.store

import kotlinx.serialization.Serializable

/**
 * A small-app template: a fixed set of source files with placeholders.
 *
 * Placeholders (replaced by [AppGenerator.generate] in both file contents
 * and file paths):
 * - `{{APP_NAME}}` — the app's display name.
 * - `{{PACKAGE}}` — the generated package name (in file paths it becomes
 *   the package's directory form, e.g. `com/codex/apps/mycounter`).
 * - `{{DESCRIPTION}}` — the app's one-line description.
 *
 * Templates are deliberately small but real: a MainActivity, a layout XML,
 * and a manifest snippet that read as a genuine minimal Android app.
 */
@Serializable
data class AppTemplate(
    val id: String,
    val name: String,
    val description: String,
    val files: Map<String, String>,
)

/** Counter app: a single screen with a number and + / − buttons. */
internal val counterTemplate: AppTemplate = AppTemplate(
    id = "counter",
    name = "Counter app",
    description = "A single screen with a number and + / − buttons.",
    files = mapOf(
        "app/src/main/java/{{PACKAGE}}/MainActivity.kt" to """
            package {{PACKAGE}}

            import android.os.Bundle
            import android.widget.Button
            import android.widget.TextView
            import androidx.appcompat.app.AppCompatActivity

            /**
             * {{APP_NAME}} — {{DESCRIPTION}}
             *
             * A minimal counter: tap + to count up, − to count down.
             */
            class MainActivity : AppCompatActivity() {

                private var count = 0

                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(R.layout.activity_main)

                    val countText = findViewById<TextView>(R.id.count_text)
                    countText.text = count.toString()

                    findViewById<Button>(R.id.button_plus).setOnClickListener {
                        count++
                        countText.text = count.toString()
                    }
                    findViewById<Button>(R.id.button_minus).setOnClickListener {
                        count--
                        countText.text = count.toString()
                    }
                }
            }
        """.trimIndent(),
        "app/src/main/res/layout/activity_main.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:gravity="center"
                android:orientation="vertical"
                android:padding="24dp">

                <TextView
                    android:id="@+id/count_text"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="0"
                    android:textSize="64sp" />

                <LinearLayout
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal">

                    <Button
                        android:id="@+id/button_minus"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="−" />

                    <Button
                        android:id="@+id/button_plus"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="+" />
                </LinearLayout>
            </LinearLayout>
        """.trimIndent(),
        "app/src/main/AndroidManifest.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="{{PACKAGE}}">

                <application
                    android:allowBackup="true"
                    android:label="{{APP_NAME}}"
                    android:supportsRtl="true"
                    android:theme="@style/Theme.AppCompat.Light.DarkActionBar">
                    <activity
                        android:name=".MainActivity"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent(),
    ),
)

/** Notes app: a single screen with a text field and a list that adds the typed note. */
internal val notesTemplate: AppTemplate = AppTemplate(
    id = "notes",
    name = "Notes app",
    description = "A single screen with a text field and a list that adds the typed note.",
    files = mapOf(
        "app/src/main/java/{{PACKAGE}}/MainActivity.kt" to """
            package {{PACKAGE}}

            import android.os.Bundle
            import android.widget.ArrayAdapter
            import android.widget.Button
            import android.widget.EditText
            import android.widget.ListView
            import androidx.appcompat.app.AppCompatActivity

            /**
             * {{APP_NAME}} — {{DESCRIPTION}}
             *
             * Type a note, tap Add, and it appears in the list below.
             * Notes live in memory only for this session.
             */
            class MainActivity : AppCompatActivity() {

                private val notes = ArrayList<String>()
                private lateinit var adapter: ArrayAdapter<String>

                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(R.layout.activity_main)

                    adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, notes)
                    findViewById<ListView>(R.id.notes_list).adapter = adapter

                    val input = findViewById<EditText>(R.id.note_input)
                    findViewById<Button>(R.id.button_add).setOnClickListener {
                        val text = input.text.toString().trim()
                        if (text.isNotEmpty()) {
                            notes.add(text)
                            adapter.notifyDataSetChanged()
                            input.text.clear()
                        }
                    }
                }
            }
        """.trimIndent(),
        "app/src/main/res/layout/activity_main.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:orientation="vertical"
                android:padding="16dp">

                <EditText
                    android:id="@+id/note_input"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:hint="Type a note" />

                <Button
                    android:id="@+id/button_add"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:text="Add note" />

                <ListView
                    android:id="@+id/notes_list"
                    android:layout_width="match_parent"
                    android:layout_height="0dp"
                    android:layout_weight="1" />
            </LinearLayout>
        """.trimIndent(),
        "app/src/main/AndroidManifest.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="{{PACKAGE}}">

                <application
                    android:allowBackup="true"
                    android:label="{{APP_NAME}}"
                    android:supportsRtl="true"
                    android:theme="@style/Theme.AppCompat.Light.DarkActionBar">
                    <activity
                        android:name=".MainActivity"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent(),
    ),
)

/** Timer app: a single screen with a countdown display and start/stop buttons. */
internal val timerTemplate: AppTemplate = AppTemplate(
    id = "timer",
    name = "Timer app",
    description = "A single screen with a countdown display and start/stop buttons.",
    files = mapOf(
        "app/src/main/java/{{PACKAGE}}/MainActivity.kt" to """
            package {{PACKAGE}}

            import android.os.Bundle
            import android.os.CountDownTimer
            import android.widget.Button
            import android.widget.TextView
            import androidx.appcompat.app.AppCompatActivity

            /**
             * {{APP_NAME}} — {{DESCRIPTION}}
             *
             * A 60-second countdown. Start begins the timer, Stop cancels it
             * and resets the display to 01:00.
             */
            class MainActivity : AppCompatActivity() {

                private var timer: CountDownTimer? = null

                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(R.layout.activity_main)

                    val display = findViewById<TextView>(R.id.timer_text)
                    display.text = format(60_000L)

                    findViewById<Button>(R.id.button_start).setOnClickListener {
                        timer?.cancel()
                        timer = object : CountDownTimer(60_000L, 1_000L) {
                            override fun onTick(millisUntilFinished: Long) {
                                display.text = format(millisUntilFinished)
                            }

                            override fun onFinish() {
                                display.text = format(0L)
                            }
                        }.also { it.start() }
                    }
                    findViewById<Button>(R.id.button_stop).setOnClickListener {
                        timer?.cancel()
                        timer = null
                        display.text = format(60_000L)
                    }
                }

                private fun format(millis: Long): String {
                    val totalSeconds = millis / 1_000L
                    val minutes = totalSeconds / 60
                    val seconds = totalSeconds % 60
                    return String.format("%02d:%02d", minutes, seconds)
                }

                override fun onDestroy() {
                    timer?.cancel()
                    super.onDestroy()
                }
            }
        """.trimIndent(),
        "app/src/main/res/layout/activity_main.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:gravity="center"
                android:orientation="vertical"
                android:padding="24dp">

                <TextView
                    android:id="@+id/timer_text"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="01:00"
                    android:textSize="64sp" />

                <LinearLayout
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal">

                    <Button
                        android:id="@+id/button_start"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="Start" />

                    <Button
                        android:id="@+id/button_stop"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="Stop" />
                </LinearLayout>
            </LinearLayout>
        """.trimIndent(),
        "app/src/main/AndroidManifest.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="{{PACKAGE}}">

                <application
                    android:allowBackup="true"
                    android:label="{{APP_NAME}}"
                    android:supportsRtl="true"
                    android:theme="@style/Theme.AppCompat.Light.DarkActionBar">
                    <activity
                        android:name=".MainActivity"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent(),
    ),
)
