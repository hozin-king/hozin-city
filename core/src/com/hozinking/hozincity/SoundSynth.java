package com.hozinking.hozincity;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

import java.io.ByteArrayOutputStream;
import java.util.Random;

/**
 * Sintesis WAV di runtime (tanpa file audio bawaan, tanpa aset berhak cipta).
 * File dibuat sekali di Gdx.files.local("sounds/") saat pertama jalan.
 * Semua PCM 16-bit mono 22050 Hz.
 */
public class SoundSynth {
    public static final int SR = 22050;

    public static void ensureSounds() {
        writeIfMissing("motor_hum.wav", hum());
        writeIfMissing("tap.wav", tap());
        writeIfMissing("coin.wav", coin());
        writeIfMissing("jump.wav", jump());
        writeIfMissing("jingle.wav", jingle());
    }

    private static void writeIfMissing(String name, byte[] wav) {
        FileHandle f = Gdx.files.local("sounds/" + name);
        if (!f.exists()) {
            f.parent().mkdirs();
            f.writeBytes(wav, false);
        }
    }

    /** Loop 1 detik yang seamless: semua komponen kelipatan bulat 55 Hz. */
    private static byte[] hum() {
        int n = SR;
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            double v = 0.50 * Math.sin(2 * Math.PI * 55 * t)
                    + 0.25 * Math.sin(2 * Math.PI * 110 * t)
                    + 0.12 * Math.sin(2 * Math.PI * 165 * t)
                    + 0.06 * Math.sin(2 * Math.PI * 220 * t);
            s[i] = (short) (v * 0.55 * 32767);
        }
        return wav(s);
    }

    /** Blip UI pendek (~70 ms), frekuensi turun 900 -> 480 Hz. */
    private static byte[] tap() {
        int n = (int) (SR * 0.07);
        short[] s = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            double f = 900 - 420 * (t / 0.07);
            phase += 2 * Math.PI * f / SR;
            double env = Math.exp(-t / 0.02);
            s[i] = (short) (Math.sin(phase) * env * 0.7 * 32767);
        }
        return wav(s);
    }

    /** Blip koin: dua nada 1320 -> 1760 Hz. */
    private static byte[] coin() {
        int n = (int) (SR * 0.22);
        short[] s = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            double f = t < 0.09 ? 1320 : 1760;
            phase += 2 * Math.PI * f / SR;
            double env = Math.exp(-t / 0.06);
            s[i] = (short) (Math.sin(phase) * env * 0.8 * 32767);
        }
        return wav(s);
    }

    /** Blip lompat: chirp naik 300 -> 750 Hz. */
    private static byte[] jump() {
        int n = (int) (SR * 0.15);
        short[] s = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            double f = 300 + 450 * (t / 0.15);
            phase += 2 * Math.PI * f / SR;
            double env = Math.exp(-t / 0.05);
            s[i] = (short) (Math.sin(phase) * env * 0.7 * 32767);
        }
        return wav(s);
    }

    /** Jingle menang: arpeggio 6 nada. */
    private static byte[] jingle() {
        double[] notes = {523, 659, 784, 1046, 784, 1318};
        double noteLen = 0.14;
        int n = (int) (SR * noteLen * notes.length);
        short[] s = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            int idx = Math.min((int) (t / noteLen), notes.length - 1);
            double lt = t - idx * noteLen;
            phase += 2 * Math.PI * notes[idx] / SR;
            double env = Math.exp(-lt / 0.09);
            s[i] = (short) (Math.sin(phase) * env * 0.75 * 32767);
        }
        return wav(s);
    }

    /** Encode PCM16 mono ke byte[] WAV (header 44 byte). */
    private static byte[] wav(short[] s) {
        int dataLen = s.length * 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataLen);
        writeStr(out, "RIFF");
        writeInt(out, 36 + dataLen);
        writeStr(out, "WAVE");
        writeStr(out, "fmt ");
        writeInt(out, 16);
        writeShort(out, 1); // PCM
        writeShort(out, 1); // mono
        writeInt(out, SR);
        writeInt(out, SR * 2);
        writeShort(out, 2); // block align
        writeShort(out, 16);
        writeStr(out, "data");
        writeInt(out, dataLen);
        for (short v : s) writeShort(out, v);
        return out.toByteArray();
    }

    private static void writeStr(ByteArrayOutputStream o, String s) {
        for (int i = 0; i < s.length(); i++) o.write(s.charAt(i));
    }

    private static void writeInt(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 24) & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }
}
