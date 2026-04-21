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
        } catch (UnsupportedAudioFileException | IOException | LineUnavailableException e) {
            e.printStackTrace();
        }
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
