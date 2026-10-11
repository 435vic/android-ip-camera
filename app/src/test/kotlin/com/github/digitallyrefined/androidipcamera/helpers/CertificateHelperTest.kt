package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CertificateHelperTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val password = "CertificatePassword123"
    // Picker document IDs need not contain the original filename or its extension.
    private val pickerUri = Uri.parse("content://documents/document/42")

    private fun certificateBytes(): ByteArray =
        CertificateHelper.generateCertificate(context, password)!!.readBytes()

    private fun savedUri(): Uri = Uri.parse(
        PreferenceManager.getDefaultSharedPreferences(context).getString("certificate_path", null)!!
    )

    @Test
    fun `imports and replaces certificates without retaining picker access`() {
        val original = certificateBytes()
        shadowOf(context.contentResolver).registerInputStream(pickerUri, ByteArrayInputStream(original))
        assertTrue(CertificateHelper.importCertificate(context, pickerUri, password))

        shadowOf(context.contentResolver).registerInputStream(pickerUri, object : InputStream() {
            override fun read(): Int = throw IOException("Picker grant is gone")
        })
        assertArrayEquals(original,
            context.contentResolver.openInputStream(savedUri())!!.use { it.readBytes() })

        val replacement = certificateBytes()
        shadowOf(context.contentResolver).registerInputStream(pickerUri, ByteArrayInputStream(replacement))
        assertTrue(CertificateHelper.importCertificate(context, pickerUri, password))
        assertArrayEquals(replacement,
            context.contentResolver.openInputStream(savedUri())!!.use { it.readBytes() })
    }

    @Test
    fun `failed imports leave the working certificate unchanged`() {
        val original = certificateBytes()
        shadowOf(context.contentResolver).registerInputStream(pickerUri, ByteArrayInputStream(original))
        assertTrue(CertificateHelper.importCertificate(context, pickerUri, password))

        shadowOf(context.contentResolver).registerInputStream(pickerUri, ByteArrayInputStream(original))
        assertFalse("Wrong password must reject the import",
            CertificateHelper.importCertificate(context, pickerUri, "WrongPassword"))
        assertArrayEquals(original,
            context.contentResolver.openInputStream(savedUri())!!.use { it.readBytes() })

        shadowOf(context.contentResolver).registerInputStream(pickerUri,
            ByteArrayInputStream("not a certificate".toByteArray()))
        assertFalse("Invalid certificate must reject the import",
            CertificateHelper.importCertificate(context, pickerUri, password))
        assertArrayEquals(original,
            context.contentResolver.openInputStream(savedUri())!!.use { it.readBytes() })
    }
}
