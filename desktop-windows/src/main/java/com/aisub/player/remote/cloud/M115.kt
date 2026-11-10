package com.aisub.player.remote.cloud

import java.math.BigInteger
import java.security.SecureRandom


/**
 * 115 下载接口的数据加解密（m115）：固定公钥的"教科书式" RSA + 两轮 XOR。
 * 移植自 SheltonZhu/115driver pkg/crypto/m115。
 */
internal object M115 {
    private val N = BigInteger(
        "8686980c0f5a24c4b9d43020cd2c22703ff3f450756529058b1cf88f09b86021" +
            "36477198a6e2683149659bd122c33592fdb5ad47944ad1ea4d36c6b172aad633" +
            "8c3bb6ac6227502d010993ac967d1aef00f0c8e038de2e4d3bc2ec368af2e9f1" +
            "0a6f1eda4f7262f136420c07c331b871bf139f74f3010e3c4fe57df3afb71683", 16,
    )
    private val E = BigInteger("10001", 16)
    private val KEY_LENGTH = N.bitLength() / 8
    private val random = SecureRandom()

    private val XOR_KEY_SEED = intArrayOf(
        0xf0, 0xe5, 0x69, 0xae, 0xbf, 0xdc, 0xbf, 0x8a, 0x1a, 0x45, 0xe8, 0xbe, 0x7d, 0xa6, 0x73, 0xb8,
        0xde, 0x8f, 0xe7, 0xc4, 0x45, 0xda, 0x86, 0xc4, 0x9b, 0x64, 0x8b, 0x14, 0x6a, 0xb4, 0xf1, 0xaa,
        0x38, 0x01, 0x35, 0x9e, 0x26, 0x69, 0x2c, 0x86, 0x00, 0x6b, 0x4f, 0xa5, 0x36, 0x34, 0x62, 0xa6,
        0x2a, 0x96, 0x68, 0x18, 0xf2, 0x4a, 0xfd, 0xbd, 0x6b, 0x97, 0x8f, 0x4d, 0x8f, 0x89, 0x13, 0xb7,
        0x6c, 0x8e, 0x93, 0xed, 0x0e, 0x0d, 0x48, 0x3e, 0xd7, 0x2f, 0x88, 0xd8, 0xfe, 0xfe, 0x7e, 0x86,
        0x50, 0x95, 0x4f, 0xd1, 0xeb, 0x83, 0x26, 0x34, 0xdb, 0x66, 0x7b, 0x9c, 0x7e, 0x9d, 0x7a, 0x81,
        0x32, 0xea, 0xb6, 0x33, 0xde, 0x3a, 0xa9, 0x59, 0x34, 0x66, 0x3b, 0xaa, 0xba, 0x81, 0x60, 0x48,
        0xb9, 0xd5, 0x81, 0x9c, 0xf8, 0x6c, 0x84, 0x77, 0xff, 0x54, 0x78, 0x26, 0x5f, 0xbe, 0xe8, 0x1e,
        0x36, 0x9f, 0x34, 0x80, 0x5c, 0x45, 0x2c, 0x9b, 0x76, 0xd5, 0x1b, 0x8f, 0xcc, 0xc3, 0xb8, 0xf5,
    ).map { it.toByte() }.toByteArray()

    private val XOR_CLIENT_KEY = intArrayOf(0x78, 0x06, 0xad, 0x4c, 0x33, 0x86, 0x5d, 0x18, 0x4c, 0x01, 0x3f, 0x46)
        .map { it.toByte() }.toByteArray()

    fun generateKey(): ByteArray = ByteArray(16).also(random::nextBytes)

    fun encode(input: ByteArray, key: ByteArray): String {
        val buf = ByteArray(16 + input.size)
        System.arraycopy(key, 0, buf, 0, 16)
        System.arraycopy(input, 0, buf, 16, input.size)
        val data = buf.copyOfRange(16, buf.size)
        xorTransform(data, deriveKey(key, 4))
        data.reverse()
        xorTransform(data, XOR_CLIENT_KEY)
        System.arraycopy(data, 0, buf, 16, data.size)
        return java.util.Base64.getEncoder().encodeToString(rsaEncrypt(buf))
    }

    fun decode(input: String, key: ByteArray): ByteArray {
        val data = rsaDecrypt(java.util.Base64.getMimeDecoder().decode(input))
        val output = data.copyOfRange(16, data.size)
        xorTransform(output, deriveKey(data.copyOfRange(0, 16), 12))
        output.reverse()
        xorTransform(output, deriveKey(key, 4))
        return output
    }

    private fun deriveKey(seed: ByteArray, size: Int): ByteArray {
        val key = ByteArray(size)
        for (i in 0 until size) {
            var v = (seed[i].toInt() + XOR_KEY_SEED[size * i].toInt()) and 0xff
            v = v xor (XOR_KEY_SEED[size * (size - i - 1)].toInt() and 0xff)
            key[i] = v.toByte()
        }
        return key
    }

    private fun xorTransform(data: ByteArray, key: ByteArray) {
        val mod = data.size % 4
        for (i in 0 until mod) data[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        for (i in mod until data.size) data[i] = (data[i].toInt() xor key[(i - mod) % key.size].toInt()).toByte()
    }

    private fun rsaEncrypt(input: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var offset = 0
        while (offset < input.size) {
            val size = minOf(KEY_LENGTH - 11, input.size - offset)
            val slice = input.copyOfRange(offset, offset + size)
            // PKCS#1 v1.5 type 2 填充（填充字节非零）
            val padSize = KEY_LENGTH - slice.size - 3
            val buf = ByteArray(KEY_LENGTH)
            buf[0] = 0; buf[1] = 2
            val pad = ByteArray(padSize).also(random::nextBytes)
            for (i in 0 until padSize) buf[2 + i] = ((pad[i].toInt() and 0xff) % 0xff + 1).toByte()
            buf[padSize + 2] = 0
            System.arraycopy(slice, 0, buf, padSize + 3, slice.size)
            val ret = BigInteger(1, buf).modPow(E, N).toUnsignedBytes()
            repeat(KEY_LENGTH - ret.size) { out.write(0) }
            out.write(ret)
            offset += size
        }
        return out.toByteArray()
    }

    private fun rsaDecrypt(input: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var offset = 0
        while (offset < input.size) {
            val size = minOf(KEY_LENGTH, input.size - offset)
            val ret = BigInteger(1, input.copyOfRange(offset, offset + size)).modPow(E, N).toUnsignedBytes()
            // 去掉填充：第一个（非首位）0 之后是明文
            for (i in ret.indices) {
                if (ret[i].toInt() == 0 && i != 0) {
                    out.write(ret, i + 1, ret.size - i - 1)
                    break
                }
            }
            offset += size
        }
        return out.toByteArray()
    }

    /** 与 Go 的 big.Int.Bytes() 一致：无符号、去掉前导 0。 */
    private fun BigInteger.toUnsignedBytes(): ByteArray {
        val b = toByteArray()
        return if (b.size > 1 && b[0].toInt() == 0) b.copyOfRange(1, b.size) else b
    }
}
