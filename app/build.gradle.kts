import java.util.Base64 as JB64
import java.io.File
import groovy.json.JsonSlurper
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.teslagarmin"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.teslagarmin"
        minSdk = 28
        targetSdk = 34
        versionCode = 6
        versionName = "0.1.5"
        // 기본값. 앱 설정 화면/동기화로 런타임 변경 가능
        buildConfigField("String", "DEFAULT_FLEET_BASE_URL", "\"https://fleet-api.prd.na.vn.cloud.tesla.com\"")
        // 개인용 시드: -PseedFromKeys=true 일 때만 ~/tesla-keys 에서 읽어 주입 (소스엔 비밀 없음)
        val seedDir = File(System.getProperty("user.home"), "tesla-keys")
        val seedOn = (project.findProperty("seedFromKeys") as String?) == "true"
        fun q(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        var sharedTokenSeed = false; var sSeedId = ""; var sClient = ""; var sRefresh = ""; var sAccess = ""; var sExp = 0L; var sVin = ""; var sPriv = ""; var sPub = ""
        if (seedOn) {
            sClient = File(seedDir, "client-id.txt").readText().trim()
            // 워치(Wear) 앱과 리프레시 토큰 체인 충돌 방지: garmin-user-tokens.json(별도 로그인 토큰) 우선
            val sep = File(seedDir, "garmin-user-tokens.json")
            val tf = if (sep.exists()) sep else File(seedDir, "user-tokens.json")
            if (!sep.exists()) logger.warn("WARNING: garmin-user-tokens.json 없음 → Wear 앱과 같은 refresh token 사용 (회전 충돌 가능)")
            sharedTokenSeed = !sep.exists()
            // 비밀 아님: 시드 파일 종류+수정시각. 값이 바뀌면 앱이 저장된 토큰을 1회 교체
            sSeedId = (if (sep.exists()) "garmin-" else "shared-") + tf.lastModified()
            val j = JsonSlurper().parse(tf as File) as Map<*, *>
            sRefresh = j["refresh_token"].toString(); sAccess = j["access_token"].toString()
            sExp = tf.lastModified() + ((j["expires_in"] as Number).toLong() * 1000)
            sVin = File(seedDir, "vin.txt").readText().trim()
            // EC 개인키(SEC1 "EC PRIVATE KEY" 또는 PKCS#8) → PKCS#8 base64. 값은 절대 로그 출력하지 않음.
            val pem = File(seedDir, "private-key.pem").readText()
            fun pemBody(tag: String) = Regex("-----BEGIN $tag-----([\\s\\S]*?)-----END $tag-----")
                .find(pem)?.groupValues?.get(1)?.replace(Regex("\\s"), "")
            val pk8 = pemBody("PRIVATE KEY")
            sPriv = pk8 ?: run {
                val sec1 = JB64.getDecoder().decode(pemBody("EC PRIVATE KEY")
                    ?: throw GradleException("private-key.pem: 지원하지 않는 형식"))
                fun derLen(n: Int): ByteArray = when {
                    n < 0x80 -> byteArrayOf(n.toByte())
                    n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
                    else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
                }
                fun tlv(tag: Int, v: ByteArray) = byteArrayOf(tag.toByte()) + derLen(v.size) + v
                // AlgorithmIdentifier { id-ecPublicKey, prime256v1 }
                val algId = tlv(0x30,
                    byteArrayOf(0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01) +
                    byteArrayOf(0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07))
                val p8 = tlv(0x30, byteArrayOf(0x02, 0x01, 0x00) + algId + tlv(0x04, sec1))
                JB64.getEncoder().encodeToString(p8)
            }
            sPub = File(seedDir, "public-key.pem").takeIf { it.exists() }?.readText()
                ?.let { Regex("-----BEGIN PUBLIC KEY-----([\\s\\S]*?)-----END").find(it)?.groupValues?.get(1) }
                ?.replace(Regex("\\s"), "") ?: ""
        }
        // 안전망: 시드 비활성 빌드는 모든 SEED_* 를 반드시 비움
        if (!seedOn) { sharedTokenSeed = false; sSeedId = ""; sClient = ""; sRefresh = ""; sAccess = ""; sExp = 0L; sVin = ""; sPriv = ""; sPub = "" }
        buildConfigField("String", "SEED_CLIENT_ID", q(sClient))
        buildConfigField("String", "SEED_REFRESH_TOKEN", q(sRefresh))
        buildConfigField("String", "SEED_ACCESS_TOKEN", q(sAccess))
        buildConfigField("long", "SEED_ACCESS_EXP", "${sExp}L")
        buildConfigField("String", "SEED_VIN", q(sVin))
        // 개인용 빌드에서만 채워짐. 최초 실행 시 Keystore/암호화 저장소로 이전 후 다시 사용하지 않음.
        buildConfigField("String", "SEED_PRIV_PKCS8", q(sPriv))
        buildConfigField("String", "SEED_PUB_SPKI", q(sPub))
        buildConfigField("String", "TOKEN_URL", "\"https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token\"")
        buildConfigField("String", "SEED_ID", q(sSeedId))
        buildConfigField("boolean", "SEED_SHARED_WITH_WEAR", sharedTokenSeed.toString())
        // 워치 앱 manifest id (Connect IQ)
        buildConfigField("String", "WATCH_APP_ID", "\"7e5a1c2b9d3f4e6a8b0c1d2e3f4a5b6c\"")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Wear 앱과 동일: 이 박스의 ~/.android/debug.keystore (개인용 사이드로드)
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.garmin.connectiq:ciq-companion-app-sdk:2.2.0@aar")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.boltfortesla:tesla-fleet-sdk-kotlin-android:3.1.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
}

// 공개 배포 전 검사: 생성된 BuildConfig 의 SEED_* 가 하나라도 비어있지 않으면 실패
tasks.register("verifySeedless") {
    dependsOn("generateReleaseBuildConfig")
    doLast {
        val f = fileTree(layout.buildDirectory.dir("generated/source/buildConfig/release")).matching { include("**/BuildConfig.java") }.singleFile
        val bad = f.readLines().filter { it.contains(" SEED_") }.filterNot {
            Regex("""SEED_\w+ = (""|0L|false);""").containsMatchIn(it)
        }.map { Regex("SEED_\\w+").find(it)!!.value }
        if (bad.isNotEmpty()) throw GradleException("공개 배포 거부: 비어있지 않은 SEED 필드 " + bad)
        println("verifySeedless OK")
    }
}
