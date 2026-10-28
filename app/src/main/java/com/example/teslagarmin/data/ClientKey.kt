package com.example.teslagarmin.data

import android.os.Build
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.util.Base64
import com.example.teslagarmin.BuildConfig
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * Tesla 개발자 앱 클라이언트 키(EC P-256).
 *  - 개인키 보관: API 31+ → Android Keystore(PURPOSE_AGREE_KEY, 추출 불가). 실패 시 EncryptedSharedPreferences.
 *  - 공개키(SPKI DER)는 비밀이 아니므로 SecureStore에 저장.
 *  - Vehicle Command Protocol 키 합의: K = SHA1(ECDH_x)[0:16]  (vehicle-command protocol.md "Key agreement")
 */
class ClientKey(private val store: SecureStore) {

    val isAvailable: Boolean
        get() = store.clientPubSpki.isNotBlank() &&
            (store.clientKeyInKeystore || store.clientPrivPkcs8.isNotBlank())

    fun publicKeySpki(): ByteArray = Base64.decode(store.clientPubSpki, Base64.NO_WRAP)

    /** 개인용 빌드 시드 → 최초 1회만 이전. 이후 시드는 사용하지 않음(플래그). */
    fun importSeedIfNeeded() {
        if (store.clientKeySeedDone || BuildConfig.SEED_PRIV_PKCS8.isBlank()) return
        if (!isAvailable) runCatching {
            importKey(BuildConfig.SEED_PRIV_PKCS8, BuildConfig.SEED_PUB_SPKI)
        }
        store.clientKeySeedDone = true
    }

    /** PKCS#8 / SPKI(base64)로 키 가져오기. 폰 Data Layer 동기화에서도 사용. */
    fun importKey(pkcs8B64: String, spkiB64: String) {
        val p8 = Base64.decode(pkcs8B64.filterNot { it.isWhitespace() }, Base64.DEFAULT)
        val spki = Base64.decode(spkiB64.filterNot { it.isWhitespace() }, Base64.DEFAULT)
        try {
            val kf = KeyFactory.getInstance("EC")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(p8))
            val pub = kf.generatePublic(X509EncodedKeySpec(spki))
            // 키쌍 일치 확인 (서명/검증)
            val msg = "tesla-watch-keycheck".toByteArray()
            val sig = Signature.getInstance("SHA256withECDSA").run { initSign(priv); update(msg); sign() }
            require(Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(msg); verify(sig) }) {
                "공개키/개인키 불일치"
            }
            val inKs = Build.VERSION.SDK_INT >= 31 && runCatching { importToKeystore(priv, pub) }.getOrDefault(false)
            store.clientPubSpki = Base64.encodeToString(spki, Base64.NO_WRAP)
            if (inKs) {
                store.clientKeyInKeystore = true
                store.clientPrivPkcs8 = ""
            } else {
                store.clientKeyInKeystore = false
                store.clientPrivPkcs8 = Base64.encodeToString(p8, Base64.NO_WRAP)
            }
            store.sessionInfo = "" // 키 변경 시 기존 세션 폐기
        } finally {
            p8.fill(0)
        }
    }

    private fun importToKeystore(priv: PrivateKey, pub: PublicKey): Boolean {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cert = selfSignedCert(priv, pub)
        ks.setEntry(
            ALIAS,
            KeyStore.PrivateKeyEntry(priv, arrayOf(cert)),
            KeyProtection.Builder(KeyProperties.PURPOSE_AGREE_KEY).build(),
        )
        // 자체 검증: Keystore 키와 소프트웨어 키의 ECDH 결과가 같은지
        val ksPriv = ks.getKey(ALIAS, null) as PrivateKey
        val a = ecdh(ksPriv, pub, "AndroidKeyStore")
        val b = ecdh(priv, pub, null)
        val ok = a.contentEquals(b)
        if (!ok) ks.deleteEntry(ALIAS)
        return ok
    }

    /** SharedSecretFetcher 구현: 차량 공개키(0x04||X||Y, 65바이트) → 16바이트 세션 키 */
    fun sharedSecret(vehiclePublicKey: ByteArray): ByteArray {
        val vehPub = uncompressedToPublicKey(vehiclePublicKey)
        val x = if (store.clientKeyInKeystore) {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ecdh(ks.getKey(ALIAS, null) as PrivateKey, vehPub, "AndroidKeyStore")
        } else {
            val p8 = Base64.decode(store.clientPrivPkcs8, Base64.NO_WRAP)
            try {
                ecdh(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(p8)), vehPub, null)
            } finally { p8.fill(0) }
        }
        val k = MessageDigest.getInstance("SHA-1").digest(x).copyOf(16)
        x.fill(0)
        return k
    }

    private fun ecdh(priv: PrivateKey, pub: PublicKey, provider: String?): ByteArray {
        val ka = if (provider != null) KeyAgreement.getInstance("ECDH", provider) else KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        return ka.generateSecret() // P-256 → 32바이트 X 좌표
    }

    /** 0x04||X||Y → ECPublicKey (클라이언트 공개키의 곡선 파라미터 재사용) */
    private fun uncompressedToPublicKey(raw: ByteArray): PublicKey {
        require(raw.size == 65 && raw[0] == 0x04.toByte()) { "차량 공개키 형식 오류(${raw.size})" }
        val params = (KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(publicKeySpki())) as ECPublicKey).params
        val pt = ECPoint(java.math.BigInteger(1, raw.copyOfRange(1, 33)), java.math.BigInteger(1, raw.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(java.security.spec.ECPublicKeySpec(pt, params))
    }

    // ---- 최소 X.509 v1 자체서명 인증서 (Keystore 가져오기에 인증서가 필요) ----
    private fun selfSignedCert(priv: PrivateKey, pub: PublicKey): X509Certificate {
        val ecdsaSha256 = tlv(0x30, byteArrayOf(0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x04, 0x03, 0x02))
        val name = tlv(0x30, tlv(0x31, tlv(0x30,
            byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03) + tlv(0x0c, "tesla-watch".toByteArray()))))
        val validity = tlv(0x30, tlv(0x17, "250101000000Z".toByteArray()) + tlv(0x17, "491231235959Z".toByteArray()))
        val tbs = tlv(0x30, byteArrayOf(0x02, 0x01, 0x01) + ecdsaSha256 + name + validity + name + pub.encoded)
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(priv); update(tbs); sign() }
        val der = tlv(0x30, tbs + ecdsaSha256 + tlv(0x03, byteArrayOf(0) + sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private fun tlv(tag: Int, v: ByteArray): ByteArray {
        val n = v.size
        val len = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        }
        return byteArrayOf(tag.toByte()) + len + v
    }

    companion object { const val ALIAS = "tesla_client_key" }
}
