package handlers;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Random;
import javax.sound.sampled.*;

public class audioHandler {
    private static Clip clip;
    private static final File MUSIC_DIR = new File("src/assets/audio/backgroundMusic");
    private static final File CLICK_SOUND_FILE = new File("src/assets/audio/soundEffects/click.wav");
    private static boolean isBackgroundMusicPlaying = false;
    private static boolean shouldKeepPlaying = true;

    public static void playClickSound() {
        if (!CLICK_SOUND_FILE.exists()) {
            System.err.println("Click sound file not found: " + CLICK_SOUND_FILE.getAbsolutePath());
            return;
        }
        try (BufferedInputStream bufferedIn = new BufferedInputStream(new FileInputStream(CLICK_SOUND_FILE))) {
            AudioInputStream audioStream = AudioSystem.getAudioInputStream(bufferedIn);
            Clip clip = AudioSystem.getClip();
            clip.open(audioStream);
            clip.start();
        } catch (Exception e) { // no audio device, unsupported file, ... never break the button that played it
            System.err.println("Could not play click sound: " + e.getMessage());
        }
    }

    /** Plays src/assets/audio/soundEffects/<name> off the Swing thread, if Sound Effects is on. */
    public static void playEffect(String name) {
        if (!configHandler.getConfigBoolean("soundEffects")) return;
        playFileAsync(new File(paths.SOUND_EFFECTS, name));
    }

    /** Plays a random .wav from soundEffects/<folder> (e.g. "winning", "losing"), if Sound Effects is on. */
    public static void playRandomEffect(String folder) {
        if (!configHandler.getConfigBoolean("soundEffects")) return;
        File[] wavs = new File(paths.SOUND_EFFECTS, folder).listFiles((_, n) -> n.toLowerCase().endsWith(".wav"));
        if (wavs == null || wavs.length == 0) return;
        playFileAsync(wavs[new Random().nextInt(wavs.length)]);
    }

    private static byte[] moveSound, captureSound;

    /** A short synthesized wooden "tock" for each move (a double knock for captures), if Sound Effects is on. */
    public static void playMoveSound(boolean capture) {
        if (!configHandler.getConfigBoolean("soundEffects")) return;
        if (moveSound == null) {
            moveSound = knock(new double[]{0}, 0.55);
            captureSound = knock(new double[]{0, 0.045}, 0.75);
        }
        byte[] pcm = capture ? captureSound : moveSound;
        Thread t = new Thread(() -> {
            try {
                AudioFormat fmt = new AudioFormat(44100, 16, 1, true, false);
                Clip c = AudioSystem.getClip();
                c.open(fmt, pcm, 0, pcm.length);
                c.addLineListener(ev -> { if (ev.getType() == LineEvent.Type.STOP) c.close(); });
                c.start();
            } catch (Exception e) {
                System.err.println("Could not play move sound: " + e.getMessage());
            }
        }, "sound");
        t.setDaemon(true);
        t.start();
    }

    /** Decaying low tones plus a click of noise, like a piece set down on a wooden board. */
    private static byte[] knock(double[] onsets, double volume) {
        int rate = 44100, n = (int) (rate * 0.16);
        double[] buf = new double[n];
        Random r = new Random(42);
        for (double onset : onsets) {
            int start = (int) (onset * rate);
            for (int i = start; i < n; i++) {
                double t = (i - start) / (double) rate;
                double body = Math.sin(2 * Math.PI * 190 * t) * Math.exp(-t * 38) + 0.6 * Math.sin(2 * Math.PI * 430 * t) * Math.exp(-t * 55);
                double click = (r.nextDouble() * 2 - 1) * Math.exp(-t * 400) * 0.5;
                buf[i] += (body + click) * volume;
            }
        }
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            int v = (int) Math.max(-32767, Math.min(32767, buf[i] * 12000));
            out[2 * i] = (byte) v;
            out[2 * i + 1] = (byte) (v >> 8);
        }
        return out;
    }

    private static void playFileAsync(File f) {
        if (!f.exists()) {
            System.err.println("Sound file not found: " + f.getAbsolutePath());
            return;
        }
        Thread t = new Thread(() -> {
            try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(f))) {
                AudioInputStream audioStream = AudioSystem.getAudioInputStream(in);
                Clip c = AudioSystem.getClip();
                c.open(audioStream);
                c.addLineListener(ev -> { if (ev.getType() == LineEvent.Type.STOP) c.close(); });
                c.start();
            } catch (Exception e) {
                System.err.println("Could not play " + f.getName() + ": " + e.getMessage());
            }
        }, "sound");
        t.setDaemon(true);
        t.start();
    }

    public static void playRandomBackgroundMusic() {
        if (!MUSIC_DIR.exists() || !MUSIC_DIR.isDirectory()) {
            System.err.println("Background music directory not found: " + MUSIC_DIR.getAbsolutePath());
            return;
        }
        File[] wavFiles = MUSIC_DIR.listFiles((dir, name) -> name.toLowerCase().endsWith(".wav"));
        if (wavFiles == null || wavFiles.length == 0) {
            System.err.println("No .wav files found in backgroundMusic directory.");
            return;
        }
        if (clip != null && clip.isOpen()) {
            clip.stop();
            clip.close();
        }
        Random rand = new Random();
        File musicFile = wavFiles[rand.nextInt(wavFiles.length)];
        try (BufferedInputStream bufferedIn = new BufferedInputStream(new FileInputStream(musicFile))) {
            AudioInputStream audioStream = AudioSystem.getAudioInputStream(bufferedIn);
            clip = AudioSystem.getClip();
            clip.open(audioStream);
            clip.start();
            isBackgroundMusicPlaying = true;
            shouldKeepPlaying = true;
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP && !clip.isRunning()) {
                    clip.close();
                    isBackgroundMusicPlaying = false;
                    if (shouldKeepPlaying) {
                        playRandomBackgroundMusic();
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            isBackgroundMusicPlaying = false;
        }
    }

    public static void stopBackgroundMusic() {
        shouldKeepPlaying = false;
        if (clip != null && clip.isOpen()) {
            clip.stop();
            clip.close();
            clip = null;
        }
        isBackgroundMusicPlaying = false;
    }

    public static boolean isBackgroundMusicPlaying() {
        return isBackgroundMusicPlaying;
    }
}
