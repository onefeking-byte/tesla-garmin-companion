package com.example.teslagarmin

import android.content.Context
import android.util.Log
import com.example.teslagarmin.data.Command
import com.example.teslagarmin.data.SecureStore
import com.example.teslagarmin.data.TeslaApi
import com.example.teslagarmin.data.TeslaApiException
import com.example.teslagarmin.data.VehicleStatus
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 워치 ↔ 폰 메시지 프로토콜 (Communications.transmit / ConnectIQ.sendMessage, 값은 Dictionary)
 *  요청: {"op":"list"} | {"op":"data","vin":V} | {"op":"cmd","vin":V,"cmd":"LOCK|UNLOCK|CLIMATE_ON|CLIMATE_OFF|TRUNK|FRUNK|RELEASE_CABLE"}
 *        | {"op":"limit","vin":V,"percent":N} | {"op":"ping"}
 *  응답: {"op":같음,"ok":true/false,"vin":V,"code":"noresp|auth|busy|key|net|fail","asleep":bool,"waking":bool, "car":{...}, "cars":[{"vin","name"}]}
 * 토큰/키는 폰에만 있고 워치로 보내지 않음.
 */
object Bridge {
    private const val TAG = "TeslaBridge"
    var status: String = "시작 안 됨"; private set
    var devices: List<String> = emptyList(); private set
    var lastEvent: String = ""; private set
    var onChange: (() -> Unit)? = null

    private var ciq: ConnectIQ? = null
    private val app = IQApp(BuildConfig.WATCH_APP_ID)
    /** 워치페이스(TeslaGarminFace): 읽기 전용(list/data)만 허용. 응답 형식은 워치 앱과 동일(FaceService가 op/cars/vin/car.batt/charging 파싱). */
    private const val FACE_APP_ID = "b21743c7b4b34699b648ddb4c6b4bfab"
    private val faceApp = IQApp(FACE_APP_ID)
    private val FACE_OPS = setOf("list", "data")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private lateinit var store: SecureStore
    private lateinit var api: TeslaApi

    private fun set(s: String) { status = s; Log.i(TAG, s); onChange?.invoke() }

    fun start(ctx: Context) {
        if (ciq != null) return
        store = SecureStore(ctx.applicationContext); api = TeslaApi(store)
        val c = ConnectIQ.getInstance(ctx.applicationContext, ConnectIQ.IQConnectType.WIRELESS)
        ciq = c
        c.initialize(ctx.applicationContext, false, object : ConnectIQ.ConnectIQListener {
            override fun onSdkReady() { set("Garmin Connect 연결됨"); register() }
            override fun onInitializeError(e: ConnectIQ.IQSdkErrorStatus?) { set("초기화 실패: $e (Garmin Connect 앱 설치/로그인 확인)"); ciq = null }
            override fun onSdkShutDown() { set("SDK 종료") }
        })
    }

    fun stop(ctx: Context) { runCatching { ciq?.shutdown(ctx.applicationContext) }; ciq = null }

    private fun register() {
        val c = ciq ?: return
        val known = runCatching { c.knownDevices }.getOrNull().orEmpty()
        devices = known.map { "${it.friendlyName} (${it.status})" }
        known.forEach { d ->
            runCatching {
                c.registerForDeviceEvents(d) { dev, st -> devices = devices.map { s -> if (s.startsWith(dev.friendlyName)) "${dev.friendlyName} ($st)" else s }; onChange?.invoke() }
                listOf(app, faceApp).forEach { a ->
                    c.registerForAppEvents(d, a) { dev, _, msg, st ->
                        if (st == ConnectIQ.IQMessageStatus.SUCCESS) msg.forEach { m -> (m as? Map<*, *>)?.let { handle(dev, a, it) } }
                    }
                }
            }.onFailure { Log.w(TAG, "register ${d.friendlyName}: ${it.message}") }
        }
        set(if (known.isEmpty()) "Garmin 기기 없음" else "대기 중 · 기기 ${known.size}대")
    }

    private fun handle(dev: IQDevice, src: IQApp, m: Map<*, *>) = scope.launch {
        val op = m["op"]?.toString() ?: return@launch
        // 워치페이스는 읽기 전용: 명령/충전한도/기타 요청은 무시(응답도 안 보냄)
        if (src.applicationId.equals(FACE_APP_ID, true) && op !in FACE_OPS) { Log.w(TAG, "face op ignored: $op"); return@launch }
        val vin = m["vin"]?.toString().orEmpty()
        lastEvent = "$op ${m["cmd"] ?: ""}".trim(); onChange?.invoke()
        val reply = HashMap<String, Any?>(); reply["op"] = op; reply["vin"] = vin
        lock.withLock {
            try {
                if (vin.isNotBlank()) store.selectVehicle(vin)
                when (op) {
                    "ping" -> {}
                    "list" -> reply["cars"] = api.listVehicles().map { (v, n) ->
                        hashMapOf<String, Any>("vin" to v, "name" to n.ifBlank { com.example.teslagarmin.data.modelName(store.carTypeFor(v), v) }) }
                    "data" -> {
                        // Galaxy 앱 statusNoWake와 동일: 데이터 요청은 절대 깨우지 않음.
                        // 잠들어 있거나(offline 포함) 응답 없으면 캐시값(asleep 표시)만 보냄.
                        val st = runCatching { api.vehicleState() }.getOrNull()
                        val fresh = if (st == "online") runCatching { api.vehicleData(wake = false) }.getOrNull() else null
                        if (fresh != null) reply["car"] = carMap(fresh)
                        else { reply["car"] = cachedMap(); reply["asleep"] = true }
                    }
                    "cmd" -> {
                        val cmd = Command.valueOf(m["cmd"].toString())
                        require(cmd in ALLOWED) { "허용되지 않은 명령" }
                        api.command(cmd)
                        reply["car"] = carMap(runCatching { api.vehicleData(wake = false) }.getOrNull())
                    }
                    "limit" -> {
                        api.setChargeLimit((m["percent"] as? Number)?.toInt() ?: 80)
                        reply["car"] = carMap(runCatching { api.vehicleData(wake = false) }.getOrNull())
                    }
                    else -> throw IllegalArgumentException("unknown op")
                }
                reply["ok"] = true
            } catch (e: Throwable) {
                // 원문 오류(JSON 등)는 워치로 보내지 않음. 짧은 코드만 → 워치에서 한국어로 표시
                Log.w(TAG, "op $op failed: ${e.javaClass.simpleName}")
                reply["ok"] = false; reply["code"] = codeOf(e)
                if (reply["code"] == "noresp" && op == "data") { reply["car"] = cachedMap(); reply["asleep"] = true }
            }
        }
        send(dev, src, reply)
    }

    private fun send(dev: IQDevice, target: IQApp, r: HashMap<String, Any?>) {
        r.values.removeAll { it == null }
        ciq?.sendMessage(dev, target, r) { _, _, st -> lastEvent += " → $st"; onChange?.invoke() }
    }

    private fun codeOf(e: Throwable): String = when {
        e is TeslaApiException && e.code == 408 -> "noresp"
        e is TeslaApiException && e.code == 401 -> "auth"
        e is TeslaApiException && e.code == 429 -> "busy"
        com.example.teslagarmin.data.isKeyError(e) -> "key"
        e is java.io.IOException -> "net"
        else -> "fail"
    }

    /** 캐시값만 (배터리/주행거리/충전/이름). 모르는 값은 보내지 않음 → 워치가 이전 값 유지 */
    private fun cachedMap(): HashMap<String, Any>? = api.cachedStatus()?.let {
        hashMapOf<String, Any>("name" to it.name, "batt" to it.batteryLevel, "km" to it.rangeKm,
            "charging" to it.chargingState.equals("Charging", true), "state" to "asleep")
    }

    private val ALLOWED = setOf(Command.LOCK, Command.UNLOCK, Command.CLIMATE_ON, Command.CLIMATE_OFF,
        Command.TRUNK, Command.FRUNK, Command.RELEASE_CABLE, Command.CHARGE_STOP,
        Command.SENTRY_ON, Command.SENTRY_OFF)

    private fun carMap(s: VehicleStatus?): HashMap<String, Any>? = s?.let {
        hashMapOf<String, Any>(
            "name" to it.name, "state" to it.state, "batt" to it.batteryLevel, "km" to it.rangeKm,
            "charging" to it.chargingState.equals("Charging", true), "kw" to (it.chargerPowerKw ?: 0),
            "mins" to (it.minutesToFull ?: 0), "limit" to (it.chargeLimitSoc ?: 80),
            "locked" to it.locked, "climate" to it.climateOn, "sentry" to it.sentryOn,
        ).apply { it.lat?.let { v -> put("lat", v.toFloat()) }; it.lon?.let { v -> put("lon", v.toFloat()) } }
    }
}
