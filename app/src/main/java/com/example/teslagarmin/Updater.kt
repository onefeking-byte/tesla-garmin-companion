package com.example.teslagarmin

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** GitHub Releases(공개 저장소)에서 새 버전 확인 → 다운로드 → sha256/서명 검증 → PackageInstaller 설치. 토큰 없음. */
class Updater(private val act: Activity, private val status: (String) -> Unit) {
    companion object {
        const val REPO = "onefeking-byte/tesla-garmin-companion"
        private const val ACTION = "com.example.teslagarmin.INSTALL_STATUS"
    }
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private fun ui(s: String) = act.runOnUiThread { status(s) }

    fun check() {
        ui("업데이트 확인 중…")
        Thread {
            try { run() } catch (e: Exception) { ui("오류: ${e.message ?: e.javaClass.simpleName}") }
        }.start()
    }

    private fun get(url: String): String {
        val r = http.newCall(Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()).execute()
        r.use { if (!it.isSuccessful) throw Exception("HTTP ${it.code}"); return it.body!!.string() }
    }

    private fun run() {
        val rel = JSONObject(get("https://api.github.com/repos/$REPO/releases/latest"))
        val assets = rel.getJSONArray("assets")
        var vjUrl: String? = null; var apkAsset: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i); val n = a.getString("name")
            if (n == "version.json") vjUrl = a.getString("browser_download_url")
            else if (n.endsWith(".apk")) apkAsset = a.getString("browser_download_url")
        }
        if (vjUrl == null) throw Exception("version.json 없음")
        val vj = JSONObject(get(vjUrl))
        val code = vj.getInt("versionCode"); val name = vj.optString("versionName", rel.optString("tag_name"))
        val apkUrl = vj.optString("apk", apkAsset ?: ""); val sha = vj.getString("sha256").lowercase()
        if (code <= BuildConfig.VERSION_CODE) { ui("최신 버전입니다 (${BuildConfig.VERSION_NAME} / $code)"); return }
        if (apkUrl.isBlank()) throw Exception("APK 주소 없음")
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            ui("새 버전 $name 있음 — '출처를 알 수 없는 앱 설치'를 허용한 뒤 다시 눌러주세요")
            act.runOnUiThread { act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}"))) }
            return
        }
        ui("새 버전 $name 다운로드 중…")
        val f = File(act.cacheDir, "update.apk")
        http.newCall(Request.Builder().url(apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("다운로드 실패 HTTP ${r.code}")
            f.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
        }
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(65536); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (got != sha) { f.delete(); throw Exception("SHA-256 불일치 — 설치 중단") }
        val pm = act.packageManager
        val newPi = pm.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw Exception("APK 파싱 실패")
        if (newPi.packageName != act.packageName) throw Exception("패키지 이름 불일치")
        val cur = pm.getPackageInfo(act.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        fun sigs(p: android.content.pm.PackageInfo) = p.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet() ?: emptySet()
        val a = sigs(newPi); val b = sigs(cur)
        if (a.isEmpty() || a != b) { f.delete(); throw Exception("서명 인증서 불일치 — 설치 중단") }
        ui("새 버전 $name 설치 중…")
        install(f)
    }

    private fun install(f: File) {
        val pi = act.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(act.packageName)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, f.length()).use { o -> f.inputStream().use { it.copyTo(o) }; s.fsync(o) }
            val rcv = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            @Suppress("DEPRECATION") val conf = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                            conf?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); if (conf != null) act.startActivity(conf)
                        }
                        PackageInstaller.STATUS_SUCCESS -> ui("설치 완료")
                        else -> ui("오류: 설치 실패 ($st) ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""}")
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 33) act.applicationContext.registerReceiver(rcv, IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED)
            else act.applicationContext.registerReceiver(rcv, IntentFilter(ACTION))
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pend = PendingIntent.getBroadcast(act, id, Intent(ACTION).setPackage(act.packageName), flags)
            s.commit(pend.intentSender)
        }
    }
}
