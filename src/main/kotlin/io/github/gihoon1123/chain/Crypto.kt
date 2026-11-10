package io.github.gihoon1123.chain

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.math.ec.ECAlgorithms
import java.math.BigInteger
import java.security.SecureRandom

fun BigInteger.quantity(): String = "0x${toString(16)}"

fun Long.quantity(): String = BigInteger.valueOf(this).quantity()

fun ByteArray.hex(): String = "0x" + joinToString("") { "%02x".format(it) }

fun String.bytes(): ByteArray {
    val value = removePrefix("0x")
    require(value.length % 2 == 0 && value.all { it.digitToIntOrNull(16) != null }) { "Invalid hex" }
    return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

fun BigInteger.unsignedBytes(): ByteArray =
    if (signum() ==
        0
    ) {
        byteArrayOf()
    } else {
        toByteArray().let { if (it[0] == 0.toByte()) it.drop(1).toByteArray() else it }
    }

data class Signature(
    val v: Long,
    val r: String,
    val s: String,
)

data class KeyPair(
    val privateKey: String,
    val publicKey: String,
    val address: String,
)

object Crypto {
    private val curve = SECNamedCurves.getByName("secp256k1")
    private val domain = ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)
    private val random = SecureRandom()

    fun hash(bytes: ByteArray): String {
        val digest = KeccakDigest(256)
        digest.update(bytes, 0, bytes.size)
        return ByteArray(32).also { digest.doFinal(it, 0) }.hex()
    }

    fun hashUtf8(value: String): String = hash(value.toByteArray(Charsets.UTF_8))

    fun publicKey(privateKey: String): String {
        val key = privateKey.bytes()
        require(key.size == 32) { "Invalid private key" }
        val scalar = BigInteger(1, key)
        require(scalar > BigInteger.ZERO && scalar < curve.n) { "Invalid private key" }
        return curve.g
            .multiply(scalar)
            .normalize()
            .getEncoded(false)
            .drop(1)
            .toByteArray()
            .hex()
            .removePrefix("0x")
    }

    fun address(privateKey: String): String = "0x" + hash(publicKey(privateKey).bytes()).takeLast(40)

    fun generateKeyPair(): KeyPair {
        var scalar: BigInteger
        do {
            scalar = BigInteger(256, random)
        } while (scalar.signum() == 0 || scalar >= curve.n)
        val key = "0x" + scalar.toString(16).padStart(64, '0')
        return KeyPair(key, publicKey(key), address(key))
    }

    fun sign(
        hash: String,
        privateKey: String,
        chainId: Long? = null,
    ): Signature {
        val message = hash.bytes()
        require(message.size == 32) { "Invalid message hash" }
        val publicKey = publicKey(privateKey)
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(BigInteger(1, privateKey.bytes()), domain))
        val rs = signer.generateSignature(message)
        val r = rs[0]
        val s = if (rs[1] > curve.n.shiftRight(1)) curve.n - rs[1] else rs[1]
        val rec = (0..1).first { recover(hash, Signature(it + 27L, padded(r), padded(s))) == publicKey }
        return Signature(if (chainId == null) rec + 27L else chainId * 2 + 35 + rec, padded(r), padded(s))
    }

    fun recover(
        hash: String,
        signature: Signature,
    ): String {
        val rec = if (signature.v >= 35) ((signature.v - 35) % 2).toInt() else (signature.v - 27).toInt()
        require(rec in 0..1) { "Invalid recovery id" }
        val r = BigInteger(1, signature.r.bytes())
        val s = BigInteger(1, signature.s.bytes())
        require(r > BigInteger.ZERO && r < curve.n && s > BigInteger.ZERO && s < curve.n)
        val encoded = byteArrayOf((2 + rec).toByte()) + r.unsignedBytes().let { ByteArray(32 - it.size) + it }
        val point = curve.curve.decodePoint(encoded)
        require(point.multiply(curve.n).isInfinity)
        val inverse = r.modInverse(curve.n)
        val e = BigInteger(1, hash.bytes())
        val q =
            ECAlgorithms
                .sumOfTwoMultiplies(
                    curve.g,
                    e
                        .negate()
                        .mod(curve.n)
                        .multiply(inverse)
                        .mod(curve.n),
                    point,
                    s.multiply(inverse).mod(curve.n),
                ).normalize()
        return q
            .getEncoded(false)
            .drop(1)
            .toByteArray()
            .hex()
            .removePrefix("0x")
    }

    fun recoverAddress(
        hash: String,
        signature: Signature,
    ): String = "0x" + hash(recover(hash, signature).bytes()).takeLast(40)

    fun verify(
        hash: String,
        signature: Signature,
        address: String,
    ): Boolean =
        runCatching {
            recoverAddress(hash, signature).equals(address, true)
        }.getOrDefault(false)

    private fun padded(value: BigInteger): String = "0x" + value.toString(16).padStart(64, '0')

    fun logsBloom(logs: List<Log>): String {
        val bloom = ByteArray(256)
        for (log in logs) {
            for (value in listOf(log.address) + log.topics) {
                val digest = hash(value.bytes()).bytes()
                for (index in listOf(0, 2, 4)) {
                    val bit = (((digest[index].toInt() and 255) * 256) + (digest[index + 1].toInt() and 255)) % 2048
                    val offset = bit / 8
                    bloom[offset] = (bloom[offset].toInt() or (1 shl (7 - bit % 8))).toByte()
                }
            }
        }
        return bloom.hex()
    }
}

object Rlp {
    fun bytes(value: ByteArray): ByteArray =
        if (value.size == 1 &&
            value[0].toInt() and 255 < 128
        ) {
            value
        } else {
            length(value.size, 128) + value
        }

    fun number(value: BigInteger): ByteArray {
        require(value.signum() >= 0)
        return bytes(value.unsignedBytes())
    }

    fun list(vararg encoded: ByteArray): ByteArray = encoded.fold(byteArrayOf()) { a, b -> a + b }.let { length(it.size, 192) + it }

    private fun length(
        size: Int,
        offset: Int,
    ): ByteArray =
        if (size <
            56
        ) {
            byteArrayOf((offset + size).toByte())
        } else {
            BigInteger.valueOf(size.toLong()).unsignedBytes().let {
                byteArrayOf(
                    (
                        offset + 55 +
                            it.size
                    ).toByte(),
                ) +
                    it
            }
        }
}
