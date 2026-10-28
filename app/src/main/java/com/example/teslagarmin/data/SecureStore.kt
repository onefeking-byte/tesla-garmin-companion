package com.example.teslagarmin.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.teslagarmin.BuildConfig

/** 토큰/설정을 EncryptedSharedPreferences(AES256, Android Keystore 마스터키)에 저장. */
class SecureStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "tesla_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var clientId: String by pref("client_id", "")
    var refreshToken: String by pref("refresh_token", "")
    var accessToken: String by pref("access_token", "")
    var accessTokenExpiry: Long
        get() = prefs.getLong("access_exp", 0L)
        set(v) = prefs.edit().putLong("access_exp", v).apply()
    /** 데이터 조회용 Fleet API 리전 호스트 (한국 = NA/APAC 호스트) */
    var fleetBaseUrl: String by pref("fleet_url", BuildConfig.DEFAULT_FLEET_BASE_URL)
    /** 서명 명령용 tesla-http-proxy 주소. 비어 있으면 fleetBaseUrl로 직접 전송(구형 차량만 동작). */
    var commandProxyUrl: String by pref("proxy_url", "")
    var vin: String by pref("vin", "")
    /** 클라이언트 공개키 SPKI DER base64 (비밀 아님) */
    var clientPubSpki: String by pref("client_pub", "")
    /** Keystore 사용 불가 시에만: 개인키 PKCS#8 base64 (EncryptedSharedPreferences 내) */
    var clientPrivPkcs8: String by pref("client_priv", "")
    var clientKeyInKeystore: Boolean
        get() = prefs.getBoolean("client_key_ks", false)
        set(v) = prefs.edit().putBoolean("client_key_ks", v).apply()
    var clientKeySeedDone: Boolean
        get() = prefs.getBoolean("client_key_seed_done", false)
        set(v) = prefs.edit().putBoolean("client_key_seed_done", v).commit().let { }
    /** Vehicle Command Protocol 세션 캐시 (자격증명 수준으로 취급) */
    var sessionInfo: String by pref("vcp_session", "")
    /** 차량 이름 표시용 캐시 */
    var cachedCarType: String
        get() = prefs.getString("car_type_$vin", "") ?: ""
        set(v) = prefs.edit().putString("car_type_$vin", v).apply()
    /** 메인 그리드 버튼 순서 (쉼표 구분 id). 비어 있으면 기본 순서 */
    /** 가상 키 미등록(보기 전용)으로 판정된 VIN 목록 (쉼표 구분) */
    var readOnlyVins: String by pref("ro_vins", "")
    fun isReadOnly(v: String = vin) = v.isNotBlank() && readOnlyVins.split(',').contains(v)
    fun markReadOnly(v: String) { if (!isReadOnly(v)) readOnlyVins = (readOnlyVins.split(',').filter { it.isNotBlank() } + v).joinToString(",") }
    fun clearReadOnly(v: String = vin) { if (isReadOnly(v)) readOnlyVins = readOnlyVins.split(',').filter { it.isNotBlank() && it != v }.joinToString(",") }
    /** 서명 명령 미지원(레거시) 차량 VIN 목록 → REST 명령 사용 */
    var legacyVins: String by pref("legacy_vins", "")
    fun isLegacy(v: String = vin) = v.isNotBlank() && legacyVins.split(',').contains(v)
    fun markLegacy(v: String) { if (!isLegacy(v)) legacyVins = (legacyVins.split(',').filter { it.isNotBlank() } + v).joinToString(",") }
    /** 차량 전환: 캐시는 VIN별로 따로 저장되므로 VIN만 바꿈 */
    fun selectVehicle(v: String) { if (v != vin) vin = v; rememberVin(v) }
    /** 캐시된 차량 목록 (쉼표 구분 VIN) — 컴플리케이션이 모델별 차량을 찾는 데 사용 */
    var knownVins: String by pref("known_vins", "")
    fun rememberVin(v: String) { if (v.isNotBlank() && !knownVins.split(',').contains(v)) knownVins = (knownVins.split(',').filter { it.isNotBlank() } + v).joinToString(",") }
    fun allKnownVins(): List<String> = (knownVins.split(',') + vin).filter { it.isNotBlank() }.distinct()
    fun carTypeFor(v: String): String = prefs.getString("car_type_$v", "") ?: ""
    fun batteryFor(v: String): Int = prefs.getInt("c_batt_$v", -1)
    fun nameFor(v: String): String = prefs.getString("c_name_$v", "") ?: ""
    /** 마지막으로 조회한 충전 중 여부 (charge_state.charging_state == "Charging") */
    fun chargingFor(v: String): Boolean = prefs.getBoolean("c_charging_$v", false)
    var buttonOrder: String by pref("btn_order", "")
    private fun ck(n: String) = "${n}_$vin"
    var cachedName: String
        get() = prefs.getString(ck("c_name"), "") ?: ""
        set(v) = prefs.edit().putString(ck("c_name"), v).apply()
    var cachedAt: Long
        get() = prefs.getLong(ck("c_at"), 0L)
        set(v) = prefs.edit().putLong(ck("c_at"), v).apply()
    var cachedBattery: Int
        get() = prefs.getInt(ck("c_batt"), -1)
        set(v) = prefs.edit().putInt(ck("c_batt"), v).apply()
    var cachedCharging: Boolean
        get() = prefs.getBoolean(ck("c_charging"), false)
        set(v) = prefs.edit().putBoolean(ck("c_charging"), v).apply()
    var cachedRangeKm: Int
        get() = prefs.getInt(ck("c_range"), -1)
        set(v) = prefs.edit().putInt(ck("c_range"), v).apply()

    val isConfigured get() = clientId.isNotBlank() && (refreshToken.isNotBlank() || accessToken.isNotBlank())

    init {
        // 개인용 빌드 최초 실행 시드 (일반 빌드에선 값이 비어 있어 아무것도 안 함)
        // 저장소가 비었거나, 별도(garmin) 토큰 시드의 ID가 이전 시드와 다르면 1회 교체
        val empty = clientId.isBlank() && refreshToken.isBlank()
        val newSeed = !BuildConfig.SEED_SHARED_WITH_WEAR && BuildConfig.SEED_ID.isNotBlank() &&
            prefs.getString("seed_id", "") != BuildConfig.SEED_ID
        if ((empty || newSeed) && BuildConfig.SEED_REFRESH_TOKEN.isNotBlank()) {
            clientId = BuildConfig.SEED_CLIENT_ID
            refreshToken = BuildConfig.SEED_REFRESH_TOKEN
            if (BuildConfig.SEED_ACCESS_EXP > System.currentTimeMillis()) {
                accessToken = BuildConfig.SEED_ACCESS_TOKEN
                accessTokenExpiry = BuildConfig.SEED_ACCESS_EXP
            } else { accessToken = ""; accessTokenExpiry = 0L }
            sessionInfo = ""
            prefs.edit().putString("seed_id", BuildConfig.SEED_ID).commit()
            fleetBaseUrl = BuildConfig.DEFAULT_FLEET_BASE_URL
            vin = BuildConfig.SEED_VIN
        }
    }

    private fun pref(key: String, def: String) = object : kotlin.properties.ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String) {
            prefs.edit().putString(key, value.trim()).apply()
        }
    }
}
