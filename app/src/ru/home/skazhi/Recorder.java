package ru.home.skazhi;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.util.Log;
import java.io.ByteArrayOutputStream;

// Records the remote mic (AUDIO_SOURCE_VOICE_RECOGNITION) as 16kHz mono PCM16.
// The remote streams over BLE in bursts, so gaps are padded with silence by wall
// clock to keep speech on a correct timeline (raw concatenation garbles ASR).
public class Recorder {
    static final String TAG = "skazhi";
    static final int RATE = 16000;
    static final int SOURCE = 6; // MediaRecorder.AudioSource.VOICE_RECOGNITION
    static final int MAX_MS = 10000;

    private volatile boolean running;
    private Thread thread;
    private volatile AudioRecord rec;   // held so stop() can unblock a blocking read()
    private final ByteArrayOutputStream out = new ByteArrayOutputStream(RATE * 2 * 4);

    public synchronized void start() {
        if (running) return;
        out.reset();
        running = true;
        thread = new Thread(this::loop, "skazhi-rec");
        thread.start();
    }

    // Stops and returns the recorded PCM (may be empty).
    public synchronized byte[] stop() {
        running = false;
        AudioRecord r = rec;
        if (r != null) { try { r.stop(); } catch (Exception ignored) {} }  // unblocks read()
        if (thread != null) { try { thread.join(2000); } catch (InterruptedException ignored) {} thread = null; }
        return out.toByteArray();
    }

    private void loop() {
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
                long expected = (System.currentTimeMillis() - t0) * 16; // 16 samples/ms @16kHz
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
