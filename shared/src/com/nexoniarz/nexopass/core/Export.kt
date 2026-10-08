package com.nexoniarz.nexopass.core

import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPPBEEncryptedData
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.operator.bc.BcPBEDataDecryptorFactory
import org.bouncycastle.openpgp.operator.bc.BcPBEKeyEncryptionMethodGenerator
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Date

/**
 * Export files are plain OpenPGP (AES-256, integrity protected), so the
 * script opens them with gpg and the app opens what the script exports.
 * The passphrase is derived from the master: only the same master opens them.
 */
object Export {
    fun encrypt(passphrase: CharArray, plain: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val gen = PGPEncryptedDataGenerator(
            BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                .setWithIntegrityPacket(true)
                .setSecureRandom(SecureRandom()),
        )
        gen.addMethod(BcPBEKeyEncryptionMethodGenerator(passphrase, BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA256)))
        gen.open(out, ByteArray(1 shl 12)).use { enc ->
            PGPLiteralDataGenerator().open(enc, PGPLiteralData.BINARY, "nexopass", plain.size.toLong(), Date()).use { it.write(plain) }
        }
        return out.toByteArray()
    }

    /** Returns null when the passphrase is wrong or the file is not a valid export. */
    fun decrypt(passphrase: CharArray, data: ByteArray): ByteArray? = try {
        val list = BcPGPObjectFactory(PGPUtil.getDecoderStream(data.inputStream())).let { f ->
            generateSequence { f.nextObject() }.filterIsInstance<PGPEncryptedDataList>().first()
        }
        val pbe = list.encryptedDataObjects.asSequence().filterIsInstance<PGPPBEEncryptedData>().first()
        val clear = pbe.getDataStream(BcPBEDataDecryptorFactory(passphrase, BcPGPDigestCalculatorProvider()))
        var factory = BcPGPObjectFactory(clear)
        var obj = factory.nextObject()
        if (obj is PGPCompressedData) {
            factory = BcPGPObjectFactory(obj.dataStream)
            obj = factory.nextObject()
        }
        val bytes = (obj as PGPLiteralData).inputStream.readBytes()
        if (pbe.isIntegrityProtected && !pbe.verify()) null else bytes
    } catch (e: Exception) {
        null
    }
}
