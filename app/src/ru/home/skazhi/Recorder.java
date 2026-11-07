package ru.home.skazhi;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.util.Log;
import java.io.ByteArrayOutputStream;

/**
 * Records the TV remote's microphone as 16 kHz mono PCM16.
 *
 * <p>The remote mic reaches apps only as {@code AUDIO_SOURCE_VOICE_RECOGNITION} and
 * only while its voice button is held. The remote streams over BLE in bursts, so
 * gaps are padded with silence by wall clock: plain concatenation squashes speech
 * in time and ruins recognition.
 *
 * <p>{@link #start()} and {@link #stop()} may be called from different threads.
 */
public class Recorder {
    static final String TAG = "skazhi";
    /** Sample rate expected by the server, Hz. */
    static final int RATE = 16000;
    /** Bytes per second of 16-bit mono audio at {@link #RATE}. */
    static final int BYTES_PER_SEC = RATE * 2;
    static final int SOURCE = 6; // MediaRecorder.AudioSource.VOICE_RECOGNITION
    /** Hard cap on one recording, in case the key-up event is lost. */
    static final int MAX_MS = 10000;

    private volatile boolean running;
    private Thread thread;
    private volatile AudioRecord rec;   // held so stop() can unblock a blocking read()
    private volatile ByteArrayOutputStream out = new ByteArrayOutputStream();

    /** Creates an idle recorder; the mic is opened only by {@link #start()}. */
    public Recorder() {}

    /** Starts recording on a background thread; no-op if already recording. */
    public synchronized void start() {
        if (running) return;
        out = new ByteArrayOutputStream(BYTES_PER_SEC * 4);  // fresh buffer: a stuck old loop keeps writing to its own
        running = true;
        thread = new Thread(this::loop, "skazhi-rec");
        thread.start();
    }

    /**
     * Stops recording and returns what was captured.
     *
     * <p>Waits up to 2 s for the recording thread to finish.
     *
     * @return the recorded PCM16 audio; empty if the mic could not be opened
     */
    public synchronized byte[] stop() {
        running = false;
        AudioRecord r = rec;
        if (r != null) { try { r.stop(); } catch (Exception ignored) {} }  // unblocks read()
        if (thread != null) { try { thread.join(2000); } catch (InterruptedException ignored) {} thread = null; }
        return out.toByteArray();
    }

    /** Recording thread body: reads the mic until stopped or {@link #MAX_MS} passes. */
    private void loop() {
        ByteArrayOutputStream out = this.out;
        AudioRecord r = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            r = new AudioRecord(SOURCE, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) { Log.e(TAG, "AudioRecord init failed"); return; }
            rec = r;
            r.startRecording();
            byte[] buf = new byte[1600];        // 50 ms
            byte[] silence = new byte[3200];
            long t0 = System.currentTimeMillis();
            long written = 0;                   // samples written so far
            while (running && System.currentTimeMillis() - t0 < MAX_MS) {
                int nb = r.read(buf, 0, buf.length);  // blocks; r.stop() makes it return <=0
                if (nb <= 0) continue;
                int ns = nb / 2;
                long expected = (System.currentTimeMillis() - t0) * (RATE / 1000); // samples due by now
                long pad = expected - written - ns;
                while (pad > 0) { int c = (int) Math.min(pad, silence.length / 2); out.write(silence, 0, c * 2); written += c; pad -= c; }
                out.write(buf, 0, nb); written += ns;
            }
        } catch (Throwable t) {
            Log.e(TAG, "record loop", t);
        } finally {
            rec = null;
            if (r != null) { try { r.stop(); } catch (Exception ignored) {} try { r.release(); } catch (Exception ignored) {} }
        }
    }
}
