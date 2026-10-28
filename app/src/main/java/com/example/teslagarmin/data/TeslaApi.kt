package com.example.teslagarmin.data

import com.example.teslagarmin.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.boltfortesla.teslafleetsdk.TeslaFleetApi
import com.boltfortesla.teslafleetsdk.net.api.vehicle.commands.ErrorResultException
import com.boltfortesla.teslafleetsdk.net.api.vehicle.commands.VehicleCommands
import com.boltfortesla.teslafleetsdk.net.api.vehicle.commands.response.VehicleCommandResponse
import kotlin.time.Duration.Companion.seconds

data class VehicleStatus(
    val name: String,
    val state: String,
    val batteryLevel: Int,
    val rangeKm: Int,
    val chargingState: String,
    val chargePortOpen: Boolean,
    val locked: Boolean,
    val climateOn: Boolean,
    val insideTempC: Double?,
    val lat: Double?,
    val sentryOn: Boolean = false,
    val driverTempC: Double? = null,
    val passengerTempC: Double? = null,
    val seatHeaterLeft: Int = 0,
    val wheelHeaterOn: Boolean = false,
    val defrostOn: Boolean = false,
    val shiftState: String = "",
    /** null = 실시간, 값 = 차량이 잠든 상태라 마지막으로 확인한 시각(epoch ms) */
    val lastKnownAt: Long? = null,
    val lon: Double?,
    /** 충전 전력 kW (charge_state.charger_power), 없으면 null */
    val chargerPowerKw: Int? = null,
    /** 완충(충전 한도)까지 남은 분 (minutes_to_full_charge) */
    val minutesToFull: Int? = null,
    /** 충전 한도 % (charge_limit_soc) */
    val chargeLimitSoc: Int? = null,
)

class TeslaApiException(val code: Int, msg: String) : Exception(msg)

/**
 * Tesla Fleet API 클라이언트.
 *  - 토큰: POST https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token (grant_type=refresh_token)
 *    리프레시 토큰은 1회용 → 응답의 새 refresh_token을 반드시 저장.
 *  - 데이터: GET {fleetBaseUrl}/api/1/vehicles/{vin}/vehicle_data
 *  - 명령:  POST {commandBase}/api/1/vehicles/{vin}/command/{name}
 *    Vehicle Command Protocol 서명은 앱 내부에서 수행(tesla-fleet-sdk-kotlin-android, signed_command).
 *    클라이언트 키가 없고 프록시 URL이 있으면 프록시로 폴백.
 */
class TeslaApi(private val store: SecureStore, private val onCacheUpdated: (() -> Unit)? = null) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = "application/json".toMediaType()

    private suspend fun token(): String = withContext(Dispatchers.IO) {
        if (store.accessToken.isNotBlank() && System.currentTimeMillis() < store.accessTokenExpiry - 60_000) {
            return@withContext store.accessToken
        }
        if (store.refreshToken.isBlank()) throw TeslaApiException(401, "refresh token 없음")
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", store.clientId)
            .add("refresh_token", store.refreshToken)
            .build()
        http.newCall(Request.Builder().url(BuildConfig.TOKEN_URL).post(body).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw TeslaApiException(r.code, "토큰 갱신 실패: $text")
            val o = JSONObject(text)
            store.accessToken = o.getString("access_token")
            o.optString("refresh_token").takeIf { it.isNotBlank() }?.let { store.refreshToken = it }
            store.accessTokenExpiry = System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000
            store.accessToken
        }
    }

    private suspend fun call(base: String, path: String, method: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            var attempt = 0
            while (true) {
                val req = Request.Builder()
                    .url(base.trimEnd('/') + path)
                    .header("Authorization", "Bearer ${token()}")
                    .apply {
                        if (method == "POST") post((body ?: JSONObject()).toString().toRequestBody(json))
                    }.build()
                http.newCall(req).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (r.code == 401 && attempt == 0) { store.accessTokenExpiry = 0; attempt++; return@use null }
                    if (!r.isSuccessful) throw TeslaApiException(r.code, "HTTP ${r.code}: $text")
                    JSONObject(text)
                }?.let { return@withContext it }
            }
            @Suppress("UNREACHABLE_CODE") JSONObject()
        }

    suspend fun listVehicles(): List<Pair<String, String>> {
        val arr = call(store.fleetBaseUrl, "/api/1/vehicles", "GET").getJSONArray("response")
        return (0 until arr.length()).map { arr.getJSONObject(it).let { v -> v.getString("vin") to v.optString("display_name") } }
            .also { l -> l.forEach { store.rememberVin(it.first) } }
    }

    suspend fun wakeUp(): String =
        call(store.fleetBaseUrl, "/api/1/vehicles/${store.vin}/wake_up", "POST")
            .getJSONObject("response").optString("state")

    /** 차량이 asleep/offline이면 vehicle_data는 408 → wake_up 후 재시도 */
    /** 깨우지 않는 상태 조회 (online/asleep/offline) */
    suspend fun vehicleState(): String =
        call(store.fleetBaseUrl, "/api/1/vehicles/${store.vin}", "GET").getJSONObject("response").optString("state")

    /** 네트워크 없이 캐시만으로 만든 상태 (차량 전환 시 즉시 표시용). 캐시 없으면 null */
    fun cachedStatus(): VehicleStatus? {
        if (store.vin.isBlank() || store.cachedBattery < 0) return null
        return VehicleStatus(
            name = store.cachedName.ifBlank { modelName(store.cachedCarType, store.vin) }, state = "",
            batteryLevel = store.cachedBattery, rangeKm = store.cachedRangeKm,
            chargingState = if (store.cachedCharging) "Charging" else "",
            chargePortOpen = false, locked = true, climateOn = false, insideTempC = null, lat = null, lon = null,
            lastKnownAt = store.cachedAt.takeIf { it > 0 },
        )
    }

    /** 새로고침: 잠들어 있으면 깨우지 않고 마지막 값 표시 */
    suspend fun statusNoWake(): VehicleStatus {
        val st = vehicleState()
        if (st == "online") return vehicleData(wake = false)
        return VehicleStatus(
            name = store.cachedName.ifBlank { modelName(store.cachedCarType, store.vin) }, state = st,
            batteryLevel = store.cachedBattery, rangeKm = store.cachedRangeKm, chargingState = "",
            chargePortOpen = false, locked = true, climateOn = false, insideTempC = null, lat = null, lon = null,
            lastKnownAt = store.cachedAt.takeIf { it > 0 },
        )
    }

    suspend fun vehicleData(wake: Boolean = false): VehicleStatus {
        // firmware 2023.38+ 에서 위치를 받으려면 endpoints에 location_data 포함 필요
        val path = "/api/1/vehicles/${store.vin}/vehicle_data?endpoints=" +
            "charge_state%3Bclimate_state%3Bvehicle_state%3Blocation_data%3Bdrive_state%3Bvehicle_config"
        val o = try {
            call(store.fleetBaseUrl, path, "GET")
        } catch (e: TeslaApiException) {
            if (e.code != 408 || !wake) throw e
            wakeUp()
            var res: JSONObject? = null
            for (i in 0 until 6) {
                delay(5000)
                res = runCatching { call(store.fleetBaseUrl, path, "GET") }.getOrNull()
                if (res != null) break
            }
            res ?: throw e
        }
        val v = o.getJSONObject("response")
        val cs = v.optJSONObject("charge_state") ?: JSONObject()
        val cl = v.optJSONObject("climate_state") ?: JSONObject()
        val vs = v.optJSONObject("vehicle_state") ?: JSONObject()
        val ds = v.optJSONObject("drive_state") ?: JSONObject()
        val vc = v.optJSONObject("vehicle_config") ?: JSONObject()
        vc.optString("car_type").takeIf { it.isNotBlank() }?.let { store.cachedCarType = it }
        // 레거시 S/X (2021 이전, car_type models/modelx) 힌트: 서명 실패 시 REST 폴백이 처리하므로 여기선 기록만 하지 않음
        val miles = cs.optDouble("battery_range", Double.NaN)
        return VehicleStatus(
            name = v.optString("display_name").takeIf { it.isNotBlank() && it != "null" }
                ?: (vs.optString("vehicle_name").takeIf { it.isNotBlank() && it != "null" })
                ?: modelName(store.cachedCarType, store.vin),
            state = v.optString("state"),
            batteryLevel = cs.optInt("battery_level", -1),
            rangeKm = if (miles.isNaN()) -1 else (miles * 1.609344).toInt(), // API 값은 마일
            chargingState = cs.optString("charging_state"),
            chargePortOpen = cs.optBoolean("charge_port_door_open"),
            locked = vs.optBoolean("locked"),
            climateOn = cl.optBoolean("is_climate_on"),
            insideTempC = cl.optDouble("inside_temp", Double.NaN).takeUnless { it.isNaN() },
            lat = ds.optDouble("latitude", Double.NaN).takeUnless { it.isNaN() },
            lon = ds.optDouble("longitude", Double.NaN).takeUnless { it.isNaN() },
            sentryOn = vs.optBoolean("sentry_mode"),
            driverTempC = cl.optDouble("driver_temp_setting", Double.NaN).takeUnless { it.isNaN() },
            passengerTempC = cl.optDouble("passenger_temp_setting", Double.NaN).takeUnless { it.isNaN() },
            seatHeaterLeft = cl.optInt("seat_heater_left", 0),
            wheelHeaterOn = cl.optBoolean("steering_wheel_heater") || cl.optInt("steering_wheel_heat_level", 0) > 0,
            defrostOn = cl.optBoolean("is_front_defroster_on") || cl.optInt("defrost_mode", 0) > 0,
            shiftState = ds.optString("shift_state").takeIf { it != "null" } ?: "",
            chargerPowerKw = cs.optInt("charger_power", -1).takeIf { it >= 0 && cs.has("charger_power") && !cs.isNull("charger_power") },
            minutesToFull = cs.optInt("minutes_to_full_charge", -1).takeIf { it > 0 && !cs.isNull("minutes_to_full_charge") },
            chargeLimitSoc = cs.optInt("charge_limit_soc", -1).takeIf { it in 1..100 },
        ).also {
            store.cachedBattery = it.batteryLevel; store.cachedRangeKm = it.rangeKm
            store.cachedCharging = it.chargingState.equals("Charging", ignoreCase = true)
            store.cachedName = it.name; store.cachedAt = System.currentTimeMillis()
            // 워치페이스 컴플리케이션 갱신 요청 (캐시만 읽음, 네트워크/차량 깨우기 없음)
            runCatching { onCacheUpdated?.invoke() }
        }
    }

    // ---------------- Vehicle Command Protocol (앱 내부 서명, 프록시 없음) ----------------
    private val clientKey = ClientKey(store)
    private var fleetSdk: TeslaFleetApi? = null

    private fun sdk(): TeslaFleetApi = fleetSdk ?: TeslaFleetApi.newInstance(clientKey.publicKeySpki()).also { api ->
        store.sessionInfo.takeIf { it.isNotBlank() }?.let { runCatching { api.loadSessionInfo(it) } }
        fleetSdk = api
    }

    private fun region(): TeslaFleetApi.Region =
        TeslaFleetApi.Region.values().firstOrNull { it.baseUrl.trimEnd('/') == store.fleetBaseUrl.trimEnd('/') }
            ?: TeslaFleetApi.Region.NA_APAC

    private val retry = TeslaFleetApi.RetryConfig(maxRetries = 3, maxBackoffDelay = 3.seconds)

    /** REST(비서명) 명령 — 2021 이전 Model S/X 등 레거시 차량용 */
    private suspend fun rest(endpoint: String, body: JSONObject?) {
        val r = call(store.fleetBaseUrl, "/api/1/vehicles/${store.vin}/command/$endpoint", "POST", body)
        val resp = r.optJSONObject("response")
        if (resp?.optBoolean("result") == false) throw TeslaApiException(0, resp.optString("reason"))
    }

    /**
     * 차량별 경로: 레거시로 기록된 VIN은 REST, 아니면 서명 → 실패(미지원/키 오류) 시 REST 폴백.
     * 둘 다 키/권한 오류일 때만 보기 전용으로 기록.
     */
    private suspend fun dispatch(endpoint: String, body: JSONObject?, block: suspend (VehicleCommands) -> Result<VehicleCommandResponse>) {
        if (store.isLegacy()) { rest(endpoint, body); return }
        val signedErr = runCatching {
            if (!clientKey.isAvailable) throw TeslaApiException(0, "클라이언트 키 없음")
            signed(block)
        }.exceptionOrNull() ?: run { store.clearReadOnly(); return }
        if (!(isProtocolUnsupported(signedErr) || isKeyError(signedErr) || !clientKey.isAvailable)) throw signedErr
        val restErr = runCatching { rest(endpoint, body) }.exceptionOrNull()
        if (restErr == null) { store.markLegacy(store.vin); store.clearReadOnly(); return }
        if (isKeyError(signedErr) && isKeyError(restErr)) {
            store.markReadOnly(store.vin); throw TeslaApiException(0, "가상 키 없음 · 보기 전용")
        }
        throw signedErr
    }

    private suspend fun signed(block: suspend (VehicleCommands) -> Result<VehicleCommandResponse>) {
        val vc = sdk().vehicleCommands(
            vin = store.vin,
            sharedSecretFetcher = { vehPub -> withContext(Dispatchers.Default) { clientKey.sharedSecret(vehPub) } },
            commandProtocolSupported = true, // 422(미지원)이면 SDK가 자동으로 비서명 경로 사용
            region = region(),
            accessToken = token(),
            retryConfig = retry,
        )
        val r = withContext(Dispatchers.IO) { block(vc) }
        runCatching { store.sessionInfo = sdk().saveSessionInfo() }
        r.getOrElse { e ->
            if (e is ErrorResultException) throw TeslaApiException(0, e.message ?: "명령 거부")
            throw e
        }
        val resp = r.getOrNull()
        if (resp is VehicleCommandResponse.CommandResponse && !resp.response.result)
            throw TeslaApiException(0, resp.response.reason)
    }

    private suspend fun ensureAwake() {
        val st = runCatching { vehicleState() }.getOrNull()
        if (st == "online") return
        wakeUp()
        for (i in 0 until 10) {
            delay(3000)
            val s2 = runCatching { vehicleState() }.getOrNull()
            if (s2 == "online") return
        }
        throw TeslaApiException(408, "차량이 깨어나지 않음")
    }

    /** 명령 전송: 서명(앱 내부) 우선, 레거시 차량은 REST. 키도 없고 레거시도 아니고 프록시 설정 시 프록시. */
    suspend fun command(cmd: Command): Boolean {
        clientKey.importSeedIfNeeded()
        if (!clientKey.isAvailable && !store.isLegacy() && store.commandProxyUrl.isNotBlank()) return proxyCommand(cmd)
        ensureAwake()
        val d: suspend (Command, suspend (VehicleCommands) -> Result<VehicleCommandResponse>) -> Unit =
            { c, blk -> dispatch(c.endpoint, c.body, blk) }
        when (cmd) {
            Command.LOCK -> d(cmd) { it.lockDoors() }
            Command.UNLOCK -> d(cmd) { it.unlockDoors() }
            Command.CLIMATE_ON -> d(cmd) { it.startAutoConditioning() }
            Command.CLIMATE_OFF -> d(cmd) { it.stopAutoConditioning() }
            Command.TRUNK -> d(cmd) { it.actuateTrunk(VehicleCommands.Trunk.REAR) }
            Command.FRUNK -> d(cmd) { it.actuateTrunk(VehicleCommands.Trunk.FRONT) }
            Command.PORT_OPEN -> d(cmd) { it.openChargePortDoor() }
            Command.PORT_CLOSE -> d(cmd) { it.closeChargePortDoor() }
            Command.CHARGE_STOP -> d(cmd) { it.stopCharging() }
            Command.SENTRY_ON -> d(cmd) { it.enableSentryMode() }
            Command.SENTRY_OFF -> d(cmd) { it.disableSentryMode() }
            Command.WHEEL_HEAT_ON -> d(cmd) { it.enableSteeringWheelHeater() }
            Command.WHEEL_HEAT_OFF -> d(cmd) { it.disableSteeringWheelHeater() }
            Command.DEFROST_ON -> d(cmd) { it.enableMaxPreconditioning(null) }
            Command.DEFROST_OFF -> d(cmd) { it.disableMaxPreconditioning() }
            Command.RELEASE_CABLE -> {
                // 충전 중이 아니면 charge_stop 실패(not_charging)는 무시
                runCatching { d(Command.CHARGE_STOP) { it.stopCharging() } }
                d(Command.PORT_OPEN) { it.openChargePortDoor() }
            }
        }
        return true
    }

    /** 파라미터가 있는 명령(온도, 시트 히터 등). 새 기능은 여기 또는 Command enum에 추가. */
    suspend fun setTemps(c: Double) = runParam("set_temps",
        JSONObject().put("driver_temp", c).put("passenger_temp", c)) { it.setTemperaturesC(c.toFloat(), c.toFloat()) }
    suspend fun setDriverSeatHeater(level: Int) = runParam("remote_seat_heater_request",
        JSONObject().put("seat_position", 0).put("level", level.coerceIn(0, 3))) {
        it.setSeatHeater(VehicleCommands.HeaterSeat.FRONT_LEFT, VehicleCommands.SeatClimateLevel.values()[level.coerceIn(0, 3)])
    }

    /** 충전 한도 (50..100%) — set_charge_limit {percent} */
    suspend fun setChargeLimit(percent: Int): Boolean {
        val p = percent.coerceIn(50, 100)
        return runParam("set_charge_limit", JSONObject().put("percent", p)) { it.setChargeLimit(p) }
    }

    private suspend fun runParam(endpoint: String, body: JSONObject,
                                 block: suspend (VehicleCommands) -> Result<VehicleCommandResponse>): Boolean {
        clientKey.importSeedIfNeeded()
        ensureAwake()
        dispatch(endpoint, body, block)
        return true
    }

    private suspend fun proxyCommand(cmd: Command): Boolean {
        val base = store.commandProxyUrl
        if (cmd == Command.RELEASE_CABLE) {
            runCatching { call(base, "/api/1/vehicles/${store.vin}/command/${Command.CHARGE_STOP.endpoint}", "POST", null) }
        }
        val r = call(base, "/api/1/vehicles/${store.vin}/command/${cmd.endpoint}", "POST", cmd.body)
        val resp = r.optJSONObject("response")
        if (resp?.optBoolean("result") == false) throw TeslaApiException(0, resp.optString("reason"))
        return true
    }
}

/** vehicle_config.car_type 우선, 없으면 VIN 4번째 문자(S/X/3/Y/C), 기본 Model X */
fun modelName(carType: String, vin: String = ""): String = when (carType.lowercase()) {
    "models", "models2", "lychee" -> "Model S"
    "modelx", "tamarind" -> "Model X"
    "model3" -> "Model 3"
    "modely" -> "Model Y"
    "cybertruck" -> "Cybertruck"
    else -> when (vin.getOrNull(3)?.uppercaseChar()) {
        'S' -> "Model S"; '3' -> "Model 3"; 'Y' -> "Model Y"; 'C' -> "Cybertruck"; else -> "Model X"
    }
}

/** 가상 키 미등록/권한 오류인지 판정 */
fun isKeyError(e: Throwable): Boolean {
    val m = (e.message ?: "") + " " + (e.cause?.message ?: "")
    return (e is TeslaApiException && e.code == 403) || Regex(
        "not (been )?paired|unknown.?key|key.?not.?(found|paired)|whitelist|UNKNOWN_KEY|public key|unauthori[sz]ed|forbidden|permission",
        RegexOption.IGNORE_CASE).containsMatchIn(m)
}

/** 차량이 서명 명령(Vehicle Command Protocol)을 지원하지 않는다는 오류인지 */
fun isProtocolUnsupported(e: Throwable): Boolean {
    val m = (e.message ?: "") + " " + (e.cause?.message ?: "")
    return (e is TeslaApiException && e.code == 422) || Regex("422|not support|unsupported|legacy|routable_message",
        RegexOption.IGNORE_CASE).containsMatchIn(m)
}

enum class Command(val endpoint: String, val body: JSONObject? = null, val needsConfirm: Boolean = false) {
    LOCK("door_lock"),
    UNLOCK("door_unlock", needsConfirm = true),
    CLIMATE_ON("auto_conditioning_start"),
    CLIMATE_OFF("auto_conditioning_stop"),
    TRUNK("actuate_trunk", JSONObject().put("which_trunk", "rear"), true),
    FRUNK("actuate_trunk", JSONObject().put("which_trunk", "front"), true),
    PORT_OPEN("charge_port_door_open"),
    PORT_CLOSE("charge_port_door_close"),
    CHARGE_STOP("charge_stop"),
    /** 충전 해제: charge_stop(실패 무시) 후 charge_port_door_open → 연결 시 커넥터 래치 해제 */
    RELEASE_CABLE("charge_port_door_open", needsConfirm = true),
    SENTRY_ON("set_sentry_mode", JSONObject().put("on", true)),
    SENTRY_OFF("set_sentry_mode", JSONObject().put("on", false)),
    WHEEL_HEAT_ON("remote_steering_wheel_heater_request", JSONObject().put("on", true)),
    WHEEL_HEAT_OFF("remote_steering_wheel_heater_request", JSONObject().put("on", false)),
    DEFROST_ON("set_preconditioning_max", JSONObject().put("on", true)),
    DEFROST_OFF("set_preconditioning_max", JSONObject().put("on", false)),
}
