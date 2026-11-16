package com.aisub.player.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WindowsAppUpdaterTest {

    @Test
    fun validatesExeMsiAndZipMagicHeadersAndRejectsCorruptDownloads() {
        val dir = Files.createTempDirectory("voxtitle-updater-test").toFile()
        try {
            val payloadSize = 600 * 1024

            // Valid EXE ("MZ")
            val exe = File(dir, "Voxtitle-2.9.97-windows-x64-Setup.exe")
            exe.writeBytes(ByteArray(payloadSize).also {
                it[0] = 0x4D
                it[1] = 0x5A
            })
            assertTrue(AppUpdater.isValidInstallerFile(exe, payloadSize.toLong()))
            // Truncated size mismatch must be rejected
            assertFalse(AppUpdater.isValidInstallerFile(exe, payloadSize.toLong() + 1024L))

            // Valid MSI (OLE2 D0 CF 11 E0)
            val msi = File(dir, "Voxtitle-2.9.97-windows-x64.msi")
            msi.writeBytes(ByteArray(payloadSize).also {
                it[0] = 0xD0.toByte()
                it[1] = 0xCF.toByte()
                it[2] = 0x11.toByte()
                it[3] = 0xE0.toByte()
            })
            assertTrue(AppUpdater.isValidInstallerFile(msi, payloadSize.toLong()))

            // Valid Portable ZIP ("PK\x03\x04")
            val zip = File(dir, "Voxtitle-2.9.97-windows-x64-portable.zip")
            zip.writeBytes(ByteArray(payloadSize).also {
                it[0] = 0x50
                it[1] = 0x4B
                it[2] = 0x03
                it[3] = 0x04
            })
            assertTrue(AppUpdater.isValidInstallerFile(zip, payloadSize.toLong()))

            // HTML error/gateway page saved as .exe must be rejected and cleaned up
            val fakeExe = File(dir, "corrupt-Setup.exe")
            val htmlBytes = ("<html><body>Gateway Error</body></html>" + " ".repeat(payloadSize)).toByteArray()
            fakeExe.writeBytes(htmlBytes)
            assertFalse(AppUpdater.isValidInstallerFile(fakeExe, htmlBytes.size.toLong()))
            assertFalse("Corrupt installer should be deleted", fakeExe.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun buildsUnicodeSafePowerShellUpdateScript() {
        val pathWithChineseAndSpaces = """C:\Users\张三\Downloads\Voxtitle (1)\Voxtitle-2.9.97-windows-x64.msi"""
        val script = AppUpdater.buildWindowsUpdatePsScript(
            ext = "msi",
            installerPath = pathWithChineseAndSpaces,
            currentPid = 12345,
            appDirPath = """C:\Program Files\Voxtitle""",
            relaunchExePath = """C:\Program Files\Voxtitle\Voxtitle.exe""",
        )
        assertTrue(script.contains("Wait-Process -Id 12345"))
        assertTrue(script.contains("msiexec.exe"))
        assertTrue(script.contains("张三"))
    }
}
