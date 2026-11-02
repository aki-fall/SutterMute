package com.example.shuttermute

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

object ShutterMuter {
    const val KEY = "csc_pref_camera_forced_shuttersound_key"
    private const val PREFS = "prefs"

    fun canWrite(c: Context) = Settings.System.canWrite(c)

    fun hasSecure(c: Context) =
        c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    fun hasPermission(c: Context) = canWrite(c) || hasSecure(c)

    fun raw(c: Context): String? = try {
        Settings.System.getString(c.contentResolver, KEY)
    } catch (e: Throwable) {
        "읽기 실패: " + e.javaClass.simpleName
    }

    fun isMuted(c: Context) = raw(c) == "0"

    fun wantMute(c: Context) = c.getSharedPreferences(PREFS, 0).getBoolean("want_mute", false)

    fun set(c: Context, mute: Boolean): String? {
        val p = c.getSharedPreferences(PREFS, 0)
        p.edit().putBoolean("want_mute", mute).apply()
        val result: String? = if (!hasPermission(c)) {
            "권한 없음: '시스템 설정 수정'을 허용하세요"
        } else {
            try {
                val ok = Settings.System.putInt(c.contentResolver, KEY, if (mute) 0 else 1)
                if (!ok) {
                    "putInt가 false를 반환했습니다"
                } else if (isMuted(c) != mute) {
                    "쓰기는 성공했지만 값이 바뀌지 않았습니다 (현재 값: " + raw(c) + ")"
                } else {
                    null
                }
            } catch (e: Throwable) {
                e.javaClass.simpleName + ": " + e.message
            }
        }
        p.edit().putString("last", result ?: "성공").apply()
        return result
    }

    fun reapply(c: Context) {
        if (wantMute(c) && !isMuted(c)) set(c, true)
    }

    fun diag(c: Context): String {
        val last = c.getSharedPreferences(PREFS, 0).getString("last", "(아직 없음)")
        val value = raw(c) ?: "없음(null)"
        return "기기: " + Build.MODEL + " / 안드로이드 " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n" +
            "시스템 설정 수정 허용: " + canWrite(c) + "\n" +
            "WRITE_SECURE_SETTINGS: " + hasSecure(c) + "\n" +
            "현재 값: " + value + "  (0=무음, 1=셔터음)\n" +
            "마지막 시도 결과: " + last
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ShutterMuter.reapply(context)
    }
}

class ToggleTile : TileService() {
    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        val err = ShutterMuter.set(this, !ShutterMuter.isMuted(this))
        if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show()
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        t.state = if (ShutterMuter.isMuted(this)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "무음 셔터"
        t.updateTile()
    }
}

class MainActivity : Activity() {
    private lateinit var toggle: Switch
    private lateinit var permBtn: Button
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL
        inner.setPadding(pad, pad * 3, pad, pad * 2)

        val title = TextView(this)
        title.text = "무음 셔터"
        title.textSize = 26f
        title.setPadding(0, 0, 0, pad)

        toggle = Switch(this)
        toggle.text = "카메라 셔터음 끄기"
        toggle.textSize = 20f
        toggle.gravity = Gravity.CENTER_VERTICAL
        toggle.setPadding(0, pad / 2, 0, pad / 2)

        permBtn = Button(this)
        permBtn.text = "시스템 설정 수정 허용하기"
        permBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + packageName)))
        }

        info = TextView(this)
        info.textSize = 14f
        info.setTextIsSelectable(true)
        info.setPadding(0, pad, 0, pad)

        val copyBtn = Button(this)
        copyBtn.text = "진단 정보 복사"
        copyBtn.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("diag", info.text))
            Toast.makeText(this, "복사됨", Toast.LENGTH_SHORT).show()
        }

        inner.addView(title)
        inner.addView(toggle)
        inner.addView(permBtn)
        inner.addView(info)
        inner.addView(copyBtn)

        val scroll = ScrollView(this)
        scroll.addView(inner)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh(null)
    }

    private fun refresh(msg: String?) {
        toggle.setOnCheckedChangeListener(null)
        toggle.isChecked = ShutterMuter.isMuted(this)
        toggle.setOnCheckedChangeListener { _, checked ->
            val err = ShutterMuter.set(this, checked)
            refresh(if (err == null) "적용됨" else "실패: " + err)
        }
        permBtn.visibility = if (ShutterMuter.canWrite(this)) View.GONE else View.VISIBLE
        val head = if (msg != null) "▶ " + msg + "\n\n" else ""
        info.text = head + ShutterMuter.diag(this)
    }
}
