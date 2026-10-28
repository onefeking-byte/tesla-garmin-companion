package com.example.teslagarmin

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.teslagarmin.data.SecureStore

/** 연결 상태 + 배터리 최적화 설정 + (선택) 별도 refresh token 입력. 검정 배경/빨강 포인트. */
class MainActivity : Activity() {
    private lateinit var st: TextView
    private val red = Color.parseColor("#E31937")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        BridgeService.start(this)
        val store = SecureStore(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(48, 96, 48, 48) }
        fun tv(t: String, size: Float, c: Int) = TextView(this).apply { text = t; textSize = size; setTextColor(c); setPadding(0, 12, 0, 12) }
        fun btn(t: String, on: () -> Unit) = Button(this).apply { text = t; setTextColor(Color.WHITE); setBackgroundColor(red)
            setOnClickListener { on() }; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 24 } }
        root.addView(tv("Tesla · Garmin", 28f, Color.WHITE).apply { paint.isFakeBoldText = true })
        root.addView(tv("버전 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", 18f, red).apply { paint.isFakeBoldText = true })
        st = tv("", 16f, Color.parseColor("#A1A1A6")); root.addView(st)
        root.addView(btn("배터리 최적화 설정 열기") {
            val pm = getSystemService(PowerManager::class.java)
            val i = if (!pm.isIgnoringBatteryOptimizations(packageName))
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(i)
        })
        root.addView(btn("브리지 다시 시작") { stopService(Intent(this, BridgeService::class.java)); BridgeService.start(this) })
        val up = tv("", 14f, Color.parseColor("#A1A1A6"))
        root.addView(btn("업데이트 확인") { Updater(this) { up.text = it }.check() })
        root.addView(up)
        if (BuildConfig.SEED_SHARED_WITH_WEAR)
            root.addView(tv("⚠ Wear 앱과 같은 refresh token으로 빌드됨. 한쪽이 갱신하면 다른 쪽이 만료될 수 있음 → 아래에 별도 토큰 입력 권장", 13f, red))
        val rt = EditText(this).apply { hint = "새 refresh token (별도 로그인, 선택)"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        root.addView(rt)
        root.addView(btn("토큰 저장") {
            val v = rt.text.toString().trim()
            if (v.length > 20) { store.refreshToken = v; store.accessToken = ""; store.accessTokenExpiry = 0; rt.setText("")
                Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show() }
        })
        root.gravity = Gravity.TOP
        setContentView(root)
        Bridge.onChange = { runOnUiThread { render() } }
        render()
    }
    private fun render() {
        st.text = "상태: ${Bridge.status}\n기기: ${Bridge.devices.joinToString().ifBlank { "-" }}\n최근: ${Bridge.lastEvent.ifBlank { "-" }}"
    }
    override fun onDestroy() { Bridge.onChange = null; super.onDestroy() }
}
