package com.example.sshlink

import com.jcraft.jsch.JSch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class OpenSshEd25519CodecTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun generatedKeyRoundTripsAndLoadsInJsch() {
        val generated = OpenSshEd25519Codec.generate("test-key")
        assertEquals(generated.publicKeyLine, OpenSshEd25519Codec.reconstructPublicLine(generated.privateKeyPem))
        val expectedBlob = Base64.getDecoder().decode(generated.publicKeyLine.split(' ')[1])
        assertArrayEquals(expectedBlob, OpenSshEd25519Codec.publicBlobFromPrivateKey(generated.privateKeyPem))

        JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        val privateFile = File(temp.root, "id_ed25519").apply { writeText(generated.privateKeyPem) }
        JSch().addIdentity(privateFile.absolutePath)
    }

    @Test(expected = IllegalArgumentException::class)
    fun tamperedKeyIsRejected() {
        val generated = OpenSshEd25519Codec.generate()
        val lines = generated.privateKeyPem.lines().toMutableList()
        val index = lines.indexOfFirst { it.isNotBlank() && !it.startsWith("-----") }
        val chars = lines[index].toCharArray()
        chars[5] = if (chars[5] == 'A') 'B' else 'A'
        lines[index] = String(chars)
        OpenSshEd25519Codec.reconstructPublicLine(lines.joinToString("\n"))
    }

    @Test fun legacyAppCommentIsMigratedWhenPublicKeyIsReconstructed() {
        val generated = OpenSshEd25519Codec.generate("ssh-tunnel-android")

        assertEquals(
            generated.publicKeyLine.replace("ssh-tunnel-android", "sshlink-android"),
            OpenSshEd25519Codec.reconstructPublicLine(generated.privateKeyPem),
        )
    }
}
