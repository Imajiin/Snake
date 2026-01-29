package pl.polsl.snake;

import android.content.Context;
import android.graphics.Point;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import java.util.Random;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.os.Build;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import android.content.Intent;

public class SnakeEngine extends SurfaceView implements Runnable, SensorEventListener {

    public enum GameState {MENU, PLAYING, DEATH_PAUSE}
    private GameState currentState = GameState.MENU;
    private Thread thread = null;
    private Context context;

    public enum Heading {UP, RIGHT, DOWN, LEFT}
    private Heading heading = Heading.RIGHT;

    private int screenX, screenY, snakeLength, bobX, bobY, blockSize, numBlocksHigh;
    private int NUM_BLOCKS_WIDE = 40;

    private long nextFrameTime;
    private final long MILLIS_PER_SECOND = 1000;
    private int score;
    private int[] snakeXs, snakeYs;
    private volatile boolean isPlaying;
    private Canvas canvas;
    private SurfaceHolder surfaceHolder;
    private Paint paint;

    private boolean isPausedForDeath = false;
    private long deathPauseStart;
    private final long DEATH_PAUSE_DURATION = 1000;

    private SensorManager sensorManager;
    private Sensor accelerometer;

    public enum ControlMode {TOUCH, ACCELEROMETER, VOICE}
    private ControlMode currentMode = ControlMode.TOUCH;

    private boolean vibrationEnabled = true, soundEnabled = true, gameVisible = true;
    private int gameSpeed = 10, appleMargin = 3;
    private float bobScale = 1.0f;

    // ZMIENNE GŁOSOWE
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private String voiceStatus = "Czekam.."; // Poprawiono: usunięto podwójną deklarację
    private long lastCommandTime = 0;
    private ToneGenerator toneGenerator;

    public SnakeEngine(Context context, Point size) {
        super(context);
        this.context = context;
        screenX = size.x;
        screenY = size.y;
        blockSize = screenX / NUM_BLOCKS_WIDE;
        numBlocksHigh = screenY / blockSize;

        surfaceHolder = getHolder();
        paint = new Paint();
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        snakeXs = new int[200];
        snakeYs = new int[200];

        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL");
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());

        toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
        newGame();
    }

    private void startListeningSafe() {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (speechRecognizer != null) {
                    speechRecognizer.destroy();
                    speechRecognizer = null;
                }
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
                setupSpeechListener();
                speechRecognizer.startListening(speechIntent);
                voiceStatus = "STARTUJĘ..";
            } catch (Exception e) {
                voiceStatus = "BŁĄD STARTU";
            }
        });
    }

    private void setupSpeechListener() {
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) { voiceStatus = "SŁUCHAM!"; }
            @Override
            public void onBeginningOfSpeech() { voiceStatus = "SŁYSZĘ..."; }
            @Override
            public void onResults(Bundle results) { processVoiceCommand(results); restart(); }
            @Override
            public void onPartialResults(Bundle partialResults) { processVoiceCommand(partialResults); }
            @Override
            public void onError(int error) {
                voiceStatus = "Błąd: " + error;
                if (error == 8) speechRecognizer.cancel();
                new Handler(Looper.getMainLooper()).postDelayed(() -> restart(), 400);
            }
            @Override
            public void onEndOfSpeech() { restart(); }
            private void restart() {
                if (currentMode == ControlMode.VOICE && currentState == GameState.PLAYING) {
                    startListeningSafe();
                }
            }
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void processVoiceCommand(Bundle bundle) {
        ArrayList<String> matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && currentState == GameState.PLAYING) {
            for (String match : matches) {
                String cmd = match.toLowerCase();
                boolean changed = false;
                if (cmd.contains("góra") && heading != Heading.DOWN) { heading = Heading.UP; changed = true; }
                else if (cmd.contains("dół") && heading != Heading.UP) { heading = Heading.DOWN; changed = true; }
                else if (cmd.contains("lewo") && heading != Heading.RIGHT) { heading = Heading.LEFT; changed = true; }
                else if (cmd.contains("prawo") && heading != Heading.LEFT) { heading = Heading.RIGHT; changed = true; }

                if (changed && System.currentTimeMillis() - lastCommandTime > 250) {
                    playDirectionSound(heading);
                    vibrateOnTurn();
                    lastCommandTime = System.currentTimeMillis();
                    break;
                }
            }
        }
    }

    public void run() {
        while (isPlaying) {
            if(updateRequired()) { update(); draw(); }
        }
    }

    public void pause() {
        isPlaying = false;
        sensorManager.unregisterListener(this);
        if (speechRecognizer != null) speechRecognizer.destroy();
        try { if (thread != null) thread.join(); } catch (InterruptedException e) {}
    }

    public void resume() {
        isPlaying = true;
        thread = new Thread(this);
        thread.start();
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
    }

    public void newGame() {
        snakeLength = 1;
        snakeXs[0] = NUM_BLOCKS_WIDE / 2;
        snakeYs[0] = numBlocksHigh / 2;
        spawnBob();
        score = 0;
        nextFrameTime = System.currentTimeMillis();
    }

    public void spawnBob() {
        Random random = new Random();
        bobX = random.nextInt(NUM_BLOCKS_WIDE - (appleMargin * 2)) + appleMargin;
        bobY = random.nextInt(numBlocksHigh - (appleMargin * 2)) + appleMargin;
    }

    private void update() {
        if (currentState != GameState.PLAYING || isPausedForDeath) {
            if (isPausedForDeath && System.currentTimeMillis() - deathPauseStart >= DEATH_PAUSE_DURATION) {
                isPausedForDeath = false;
                newGame();
            }
            return;
        }
        float distanceX = Math.abs(snakeXs[0] - bobX);
        float distanceY = Math.abs(snakeYs[0] - bobY);
        float hitRange = bobScale / 2.0f;

        if (distanceX <= hitRange && distanceY <= hitRange){
            eatBob();
        }
        moveSnake();
        checkAppleWarning();
        checkEngineWarning();

        if(detectDeath()){
            handleDeath();
        }
    }

    private void handleDeath() {
        isPausedForDeath = true;
        deathPauseStart = System.currentTimeMillis();
        if(vibrationEnabled) {
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            v.vibrate(VibrationEffect.createOneShot(600, 255));
        }
    }

    public void draw() {
        if (surfaceHolder.getSurface().isValid()) {
            canvas = surfaceHolder.lockCanvas();
            canvas.drawColor(Color.argb(255, 26, 128, 182));

            if (currentState == GameState.MENU) {
                drawMenu();
            } else {
                paint.setColor(Color.WHITE);
                paint.setTextSize(40); // Większa czcionka HUD
                canvas.drawText("Wynik: " + score, 20, 50, paint);
                canvas.drawText("Głos: " + voiceStatus, 20, 100, paint);

                if (gameVisible) {
                    paint.setColor(Color.RED);
                    for (int i = 0; i < snakeLength; i++) {
                        canvas.drawRect(snakeXs[i] * blockSize, snakeYs[i] * blockSize,
                                (snakeXs[i] * blockSize) + blockSize, (snakeYs[i] * blockSize) + blockSize, paint);
                    }
                    paint.setColor(Color.GREEN);
                    float centerX = bobX * blockSize + (blockSize / 2.0f);
                    float centerY = bobY * blockSize + (blockSize / 2.0f);
                    float scaledSize = (blockSize / 2.0f) * bobScale;
                    canvas.drawRect(centerX - scaledSize, centerY - scaledSize, centerX + scaledSize, centerY + scaledSize, paint);
                }

                paint.setColor(Color.argb(150, 255, 255, 255));
                canvas.drawRect(screenX - 200, 10, screenX - 10, 100, paint);
                paint.setColor(Color.BLACK);
                paint.setTextSize(40);
                canvas.drawText("MENU", screenX - 180, 70, paint);
            }
            surfaceHolder.unlockCanvasAndPost(canvas);
        }
    }

    private void drawMenu() {
        int p = 20, h = screenY / 15, w = (screenX / 2) - (p * 2);

        // Nagłówki - Rozmiar 55, bez skrótów
        paint.setTextSize(55);
        paint.setFakeBoldText(true);
        paint.setColor(Color.WHITE);
        canvas.drawText("STEROWANIE", p, 70, paint);

        // Kafelki - Rozmiar 35 dla lepszej czytelności
        paint.setTextSize(35);
        drawTile(p, 90, w, h, "DOTYK", currentMode == ControlMode.TOUCH);
        drawTile(screenX/2 + p, 90, w, h, "AKCELEROMETR", currentMode == ControlMode.ACCELEROMETER);
        drawTile(p, 90 + h + p, screenX - (p*2), h, "STEROWANIE GŁOSEM: " + voiceStatus, currentMode == ControlMode.VOICE);

        int startY2 = 90 + (h+p)*2 + 60;
        paint.setTextSize(55);
        canvas.drawText("INFORMACJA ZWROTNA", p, startY2 - 20, paint);

        paint.setTextSize(35);
        drawTile(p, startY2, w, h, "OBRAZ", gameVisible);
        drawTile(screenX/2 + p, startY2, w, h, "DŹWIĘK", soundEnabled);
        drawTile(p, startY2 + h + p, screenX - (p*2), h, "WIBRACJE", vibrationEnabled);

        int startY3 = startY2 + (h+p)*2 + 60;
        paint.setTextSize(55);
        canvas.drawText("PARAMETRY", p, startY3 - 20, paint);

        paint.setTextSize(35);
        drawTile(p, startY3, w, h, "SZYBKOŚĆ [-]", false);
        drawTile(screenX/2 + p, startY3, w, h, "SZYBKOŚĆ [+]", false);

        int startY4 = startY3 + h + p;
        drawTile(p, startY4, w, h, "ROZMIAR JABŁKA [-]", false);
        drawTile(screenX/2 + p, startY4, w, h, "ROZMIAR JABŁKA [+]", false);

        paint.setColor(Color.WHITE);
        paint.setTextSize(40);
        canvas.drawText("Szybkość: " + gameSpeed + " | Skala: " + String.format("%.1f", bobScale), p, startY4 + h + 60, paint);

        paint.setColor(Color.GREEN);
        canvas.drawRect(p, screenY - 180, screenX - p, screenY - 30, paint);
        paint.setColor(Color.BLACK);
        paint.setTextSize(60);
        canvas.drawText("ZACZNIJ GRĘ", screenX/2 - 170, screenY - 90, paint);
    }

    private void drawTile(int x, int y, int w, int h, String text, boolean active) {
        paint.setColor(active ? Color.YELLOW : Color.DKGRAY);
        canvas.drawRect(x, y, x + w, y + h, paint);
        paint.setColor(active ? Color.BLACK : Color.WHITE);
        canvas.drawText(text, x + 20, y + h/2 + 10, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent motionEvent) {
        if (motionEvent.getAction() == MotionEvent.ACTION_UP) {
            float x = motionEvent.getX(), y = motionEvent.getY();

            if (currentState == GameState.MENU) {
                int p = 20, h = screenY / 15;
                int startY2 = 90 + (h+p)*2 + 60;
                int startY3 = startY2 + (h+p)*2 + 60;
                int startY4 = startY3 + h + p;

                // Sekcja 1: Sterowanie
                if (y > 90 && y < 90 + h) {
                    if (x < screenX / 2) currentMode = ControlMode.TOUCH;
                    else currentMode = ControlMode.ACCELEROMETER;
                } else if (y > 90 + h + p && y < 90 + (h + p) * 2) {
                    currentMode = ControlMode.VOICE;
                }
                // Sekcja 2: Feedback (Zabezpieczenie min. 1 feedback)
                else if (y > startY2 && y < startY2 + h) {
                    if (x < screenX / 2) {
                        if (!gameVisible || soundEnabled || vibrationEnabled) gameVisible = !gameVisible;
                    } else {
                        if (!soundEnabled || gameVisible || vibrationEnabled) soundEnabled = !soundEnabled;
                    }
                } else if (y > startY2 + h + p && y < startY2 + (h + p) * 2) {
                    if (!vibrationEnabled || gameVisible || soundEnabled) vibrationEnabled = !vibrationEnabled;
                }
                // Sekcja 3: Parametry
                else if (y > startY3 && y < startY3 + h) {
                    if (x < screenX / 2 && gameSpeed > 1) gameSpeed--;
                    else if (x >= screenX / 2 && gameSpeed < 30) gameSpeed++;
                } else if (y > startY4 && y < startY4 + h) {
                    if (x < screenX / 2 && bobScale > 0.5f) bobScale -= 0.1f;
                    else if (x >= screenX / 2 && bobScale < 2.0f) bobScale += 0.1f;
                }
                // Start
                else if (y > screenY - 180) {
                    newGame();
                    currentState = GameState.PLAYING;
                    if (currentMode == ControlMode.VOICE) startListeningSafe();
                }
            } else {
                // Powrót do MENU
                if (x > screenX - 200 && y < 100) {
                    currentState = GameState.MENU;
                    if (speechRecognizer != null) speechRecognizer.destroy();
                } else if (currentMode == ControlMode.TOUCH) {
                    handleTouchTurn(x);
                }
            }
        }
        return true;
    }

    private void handleTouchTurn(float x) {
        if (x >= screenX / 2) {
            if (heading == Heading.UP) heading = Heading.RIGHT;
            else if (heading == Heading.RIGHT) heading = Heading.DOWN;
            else if (heading == Heading.DOWN) heading = Heading.LEFT;
            else if (heading == Heading.LEFT) heading = Heading.UP;
        } else {
            if (heading == Heading.UP) heading = Heading.LEFT;
            else if (heading == Heading.LEFT) heading = Heading.DOWN;
            else if (heading == Heading.DOWN) heading = Heading.RIGHT;
            else if (heading == Heading.RIGHT) heading = Heading.UP;
        }
        playDirectionSound(heading); vibrateOnTurn();
    }

    private void checkAppleWarning() {
        if (Math.abs(snakeXs[0]-bobX) <= 5 && Math.abs(snakeYs[0]-bobY) <= 5) {
            if (soundEnabled) toneGenerator.startTone(ToneGenerator.TONE_DTMF_B, 30);
            if (vibrationEnabled) ((Vibrator)context.getSystemService(Context.VIBRATOR_SERVICE)).vibrate(VibrationEffect.createOneShot(15, 40));
        }
    }

    private void checkEngineWarning() {
        if ((snakeXs[0] <= 2 || snakeXs[0] >= NUM_BLOCKS_WIDE-3 || snakeYs[0] <= 2 || snakeYs[0] >= numBlocksHigh-3) && vibrationEnabled) {
            ((Vibrator)context.getSystemService(Context.VIBRATOR_SERVICE)).vibrate(VibrationEffect.createOneShot(20, 50));
        }
    }

    private void eatBob() { snakeLength++; spawnBob(); score++; if (vibrationEnabled) ((Vibrator)context.getSystemService(Context.VIBRATOR_SERVICE)).vibrate(VibrationEffect.createOneShot(50, 150)); if (soundEnabled) toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 200); }
    private void moveSnake() { for (int i = snakeLength; i > 0; i--) { snakeXs[i] = snakeXs[i - 1]; snakeYs[i] = snakeYs[i - 1]; } switch (heading) { case UP: snakeYs[0]--; break; case RIGHT: snakeXs[0]++; break; case DOWN: snakeYs[0]++; break; case LEFT: snakeXs[0]--; break; } }
    private boolean detectDeath() { if (snakeXs[0] == -1 || snakeXs[0] >= NUM_BLOCKS_WIDE || snakeYs[0] == -1 || snakeYs[0] >= numBlocksHigh) return true; for (int i = snakeLength - 1; i > 0; i--) { if (i > 4 && snakeXs[0] == snakeXs[i] && snakeYs[0] == snakeYs[i]) return true; } return false; }
    private boolean updateRequired() { if(nextFrameTime <= System.currentTimeMillis()){ nextFrameTime = System.currentTimeMillis() + MILLIS_PER_SECOND / gameSpeed; return true; } return false; }
    private void playDirectionSound(Heading h) { if(!soundEnabled) return; int t = -1; if(h == Heading.UP) t = ToneGenerator.TONE_DTMF_1; else if(h == Heading.DOWN) t = ToneGenerator.TONE_DTMF_7; else if(h == Heading.LEFT) t = ToneGenerator.TONE_DTMF_4; else if(h == Heading.RIGHT) t = ToneGenerator.TONE_DTMF_6; if(t != -1) toneGenerator.startTone(t, 100); }
    private void vibrateOnTurn() { if(vibrationEnabled) ((Vibrator)context.getSystemService(Context.VIBRATOR_SERVICE)).vibrate(VibrationEffect.createOneShot(10, 30)); }
    @Override public void onSensorChanged(SensorEvent event) { if (currentState != GameState.PLAYING || currentMode != ControlMode.ACCELEROMETER || isPausedForDeath) return; float x = event.values[0], y = event.values[1], th = 3.0f; if(Math.abs(x) > Math.abs(y)){ if(x > th && heading != Heading.RIGHT) { heading = Heading.LEFT; playDirectionSound(heading); vibrateOnTurn(); } else if (x < -th && heading != Heading.LEFT) { heading = Heading.RIGHT; playDirectionSound(heading); vibrateOnTurn(); } } else { if (y > th && heading != Heading.UP) { heading = Heading.DOWN; playDirectionSound(heading); vibrateOnTurn(); } else if (y < -th && heading != Heading.DOWN) { heading = Heading.UP; playDirectionSound(heading); vibrateOnTurn(); } } }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}