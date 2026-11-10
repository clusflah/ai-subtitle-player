package com.aisub.player.desktop

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * Windows WASAPI Loopback 系统内录采集器（16kHz 单声道 Float PCM）。
 * 首次运行时利用 Windows 系统自带的 .NET Framework 编译器 (csc.exe) 编译轻量的原生
 * CoreAudio WASAPI Loopback 采集器 (wasapi-loopback.exe)，直接捕获系统扬声器正在播放的声音，
 * 无需安装虚拟声卡，也不受立体声混音开关限制。
 */
object WasapiLoopbackCapture {
    const val SAMPLE_RATE = 16_000

    private val listeners = CopyOnWriteArrayList<(FloatArray) -> Unit>()
    @Volatile
    private var captureThread: Thread? = null
    @Volatile
    private var activeProcess: Process? = null
    @Volatile
    private var running = false

    val isCapturing: Boolean get() = running

    fun addListener(listener: (FloatArray) -> Unit) {
        listeners.add(listener)
        synchronized(this) {
            if (!running) {
                startInternal()
            }
        }
    }

    fun removeListener(listener: (FloatArray) -> Unit) {
        listeners.remove(listener)
        synchronized(this) {
            if (listeners.isEmpty() && running) {
                stopInternal()
            }
        }
    }

    private fun startInternal() {
        running = true
        captureThread = Thread({
            try {
                while (running && listeners.isNotEmpty()) {
                    try {
                        val exe = ensureWasapiExe()
                        if (exe != null && exe.isFile) {
                            runWasapiProcess(exe)
                        } else {
                            runJavaSoundFallback()
                        }
                    } catch (e: Throwable) {
                        android.util.Log.w("Voxtitle-WASAPI", "系统声音捕获异常: ${e.message}", e)
                    }
                    if (running && listeners.isNotEmpty()) {
                        runCatching { Thread.sleep(800) }
                    }
                }
            } finally {
                running = false
            }
        }, "wasapi-loopback-capture").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopInternal() {
        running = false
        runCatching { activeProcess?.destroyForcibly() }
        activeProcess = null
        captureThread?.interrupt()
        captureThread = null
    }

    private fun runWasapiProcess(exe: File) {
        val proc = ProcessBuilder(exe.absolutePath)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        activeProcess = proc
        try {
            val ins = proc.inputStream
            // 每次读取 1600 采样点 (100ms, 3200 字节)
            val buf = ByteArray(3200)
            var offset = 0
            while (running && proc.isAlive) {
                val n = ins.read(buf, offset, buf.size - offset)
                if (n < 0) break
                val total = offset + n
                val samplesCount = total / 2
                if (samplesCount >= 800) { // 至少 50ms
                    val consumedBytes = samplesCount * 2
                    val sb = ByteBuffer.wrap(buf, 0, consumedBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val floats = FloatArray(samplesCount) { sb.get(it) / 32768f }
                    for (l in listeners) {
                        runCatching { l(floats) }
                    }
                    offset = total - consumedBytes
                    if (offset > 0) {
                        System.arraycopy(buf, consumedBytes, buf, 0, offset)
                    }
                } else {
                    offset = total
                }
            }
        } finally {
            runCatching { proc.destroyForcibly() }
            if (activeProcess === proc) activeProcess = null
        }
    }

    private fun runJavaSoundFallback() {
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val line = (AudioSystem.getLine(info) as? TargetDataLine) ?: return
        line.open(format, SAMPLE_RATE * 2)
        line.start()
        try {
            val buf = ByteArray(3200)
            while (running) {
                val n = line.read(buf, 0, buf.size)
                if (n <= 0) continue
                val count = n / 2
                val sb = ByteBuffer.wrap(buf, 0, count * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val floats = FloatArray(count) { sb.get(it) / 32768f }
                for (l in listeners) {
                    runCatching { l(floats) }
                }
            }
        } finally {
            runCatching { line.stop() }
            runCatching { line.close() }
        }
    }

    @Synchronized
    private fun ensureWasapiExe(): File? {
        val exeFile = File(DesktopBinaryFinder.binDir, "wasapi-loopback-v2.exe")
        if (exeFile.isFile && exeFile.length() > 1024) return exeFile

        val cscCandidates = listOf(
            File("C:/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe"),
            File("C:/Windows/Microsoft.NET/Framework/v4.0.30319/csc.exe"),
        )
        val csc = cscCandidates.firstOrNull { it.isFile } ?: return null
        val csFile = File(DesktopBinaryFinder.binDir, "wasapi-loopback-v2.cs")
        return runCatching {
            csFile.writeText(WASAPI_CS_SOURCE, Charsets.UTF_8)
            val proc = ProcessBuilder(
                csc.absolutePath,
                "/nologo",
                "/optimize+",
                "/out:${exeFile.absolutePath}",
                csFile.absolutePath,
            ).redirectErrorStream(true).start()
            proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            csFile.delete()
            if (code == 0 && exeFile.isFile) exeFile else null
        }.getOrNull()
    }

    private const val WASAPI_CS_SOURCE = """
using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

class WasapiLoopback
{
    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    private class MMDeviceEnumeratorComObject { }

    [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        int EnumAudioEndpoints(int dataFlow, int dwStateMask, out IntPtr ppDevices);
        int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice ppEndpoint);
    }

    [Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        int Activate(ref Guid iid, int dwClsCtx, IntPtr pActivationParams, [MarshalAs(UnmanagedType.IUnknown)] out object ppInterface);
    }

    [Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioClient
    {
        int Initialize(int ShareMode, int StreamFlags, long hnsBufferDuration, long hnsPeriodicity, IntPtr pFormat, IntPtr AudioSessionGuid);
        int GetBufferSize(out uint pNumBufferFrames);
        int GetStreamLatency(out long phnsLatency);
        int GetCurrentPadding(out uint pNumPaddingFrames);
        int IsFormatSupported(int ShareMode, IntPtr pFormat, out IntPtr ppClosestMatch);
        int GetMixFormat(out IntPtr ppDeviceFormat);
        int GetDevicePeriod(out long phnsDefaultDevicePeriod, out long phnsMinimumDevicePeriod);
        int Start();
        int Stop();
        int Reset();
        int SetEventHandle(IntPtr eventHandle);
        int GetService(ref Guid riid, [MarshalAs(UnmanagedType.IUnknown)] out object ppv);
    }

    [Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioCaptureClient
    {
        int GetBuffer(out IntPtr ppData, out uint pNumFramesToRead, out uint pdwFlags, out ulong pu64DevicePosition, out ulong pu64QPCPosition);
        int ReleaseBuffer(uint NumFramesRead);
        int GetNextPacketSize(out uint pNumFramesInNextPacket);
    }

    static int Main()
    {
        try
        {
            var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumeratorComObject();
            IMMDevice device;
            // eRender = 0, eConsole = 0
            int hr = enumerator.GetDefaultAudioEndpoint(0, 0, out device);
            if (hr != 0 || device == null) return 1;

            Guid iidAudioClient = new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
            object objClient;
            hr = device.Activate(ref iidAudioClient, 23, IntPtr.Zero, out objClient);
            if (hr != 0 || objClient == null) return 2;
            var audioClient = (IAudioClient)objClient;

            IntPtr mixFormatPtr;
            hr = audioClient.GetMixFormat(out mixFormatPtr);
            if (hr != 0 || mixFormatPtr == IntPtr.Zero) return 3;

            short formatTag = Marshal.ReadInt16(mixFormatPtr, 0);
            short channels = Marshal.ReadInt16(mixFormatPtr, 2);
            int sampleRate = Marshal.ReadInt32(mixFormatPtr, 4);
            short bitsPerSample = Marshal.ReadInt16(mixFormatPtr, 14);
            bool isFloat = (formatTag == 3);
            if (formatTag == -2) // WAVE_FORMAT_EXTENSIBLE (0xFFFE)
            {
                byte[] subFormat = new byte[16];
                Marshal.Copy(IntPtr.Add(mixFormatPtr, 24), subFormat, 0, 16);
                Guid subGuid = new Guid(subFormat);
                if (subGuid == new Guid("00000003-0000-0010-8000-00aa00389b71")) isFloat = true;
            }

            // AUDCLNT_SHAREMODE_SHARED = 0, AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000
            hr = audioClient.Initialize(0, 0x00020000, 10000000, 0, mixFormatPtr, IntPtr.Zero);
            if (hr != 0) return 4;

            Guid iidCaptureClient = new Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317");
            object objCapture;
            hr = audioClient.GetService(ref iidCaptureClient, out objCapture);
            if (hr != 0 || objCapture == null) return 5;
            var captureClient = (IAudioCaptureClient)objCapture;

            audioClient.Start();
            Stream stdout = Console.OpenStandardOutput();
            int targetRate = 16000;
            double step = (double)sampleRate / targetRate;
            double pos = 0.0;
            int bytesPerSample = bitsPerSample / 8;
            int frameBytes = channels * bytesPerSample;
            byte[] outBuf = new byte[6400];
            byte[] silenceBuf = new byte[1600]; // 50ms 16kHz 16-bit mono silence
            int silentLoops = 0;

            while (true)
            {
                uint packetLength = 0;
                captureClient.GetNextPacketSize(out packetLength);
                if (packetLength == 0)
                {
                    Thread.Sleep(15);
                    silentLoops++;
                    if (silentLoops >= 3)
                    {
                        silentLoops = 0;
                        stdout.Write(silenceBuf, 0, silenceBuf.Length);
                        stdout.Flush();
                    }
                    continue;
                }
                silentLoops = 0;

                while (packetLength > 0)
                {
                    IntPtr dataPtr;
                    uint numFrames;
                    uint flags;
                    ulong devPos;
                    ulong qpcPos;
                    hr = captureClient.GetBuffer(out dataPtr, out numFrames, out flags, out devPos, out qpcPos);
                    if (hr != 0) break;

                    bool silent = (flags & 2) != 0; // AUDCLNT_BUFFERFLAGS_SILENT = 2
                    int nFrames = (int)numFrames;
                    float[] mono = new float[nFrames];
                    if (!silent && nFrames > 0)
                    {
                        int totalBytes = nFrames * frameBytes;
                        byte[] raw = new byte[totalBytes];
                        Marshal.Copy(dataPtr, raw, 0, totalBytes);
                        for (int i = 0; i < nFrames; i++)
                        {
                            float sum = 0f;
                            int baseIdx = i * frameBytes;
                            for (int c = 0; c < channels; c++)
                            {
                                int off = baseIdx + c * bytesPerSample;
                                if (isFloat && bytesPerSample == 4)
                                {
                                    sum += BitConverter.ToSingle(raw, off);
                                }
                                else if (bytesPerSample == 2)
                                {
                                    sum += BitConverter.ToInt16(raw, off) / 32768f;
                                }
                            }
                            mono[i] = sum / channels;
                        }
                    }

                    captureClient.ReleaseBuffer(numFrames);

                    int outIdx = 0;
                    while (pos < nFrames)
                    {
                        int idx = (int)pos;
                        if (idx >= nFrames) break;
                        float v = mono[idx];
                        if (v > 1f) v = 1f;
                        else if (v < -1f) v = -1f;
                        short s = (short)(v * 32767f);
                        if (outIdx + 2 > outBuf.Length)
                        {
                            stdout.Write(outBuf, 0, outIdx);
                            outIdx = 0;
                        }
                        outBuf[outIdx++] = (byte)(s & 0xFF);
                        outBuf[outIdx++] = (byte)((s >> 8) & 0xFF);
                        pos += step;
                    }
                    pos -= nFrames;
                    if (pos < 0) pos = 0;
                    if (outIdx > 0)
                    {
                        stdout.Write(outBuf, 0, outIdx);
                        stdout.Flush();
                    }

                    captureClient.GetNextPacketSize(out packetLength);
                }
            }
        }
        catch
        {
            return 99;
        }
    }
}
"""
}
