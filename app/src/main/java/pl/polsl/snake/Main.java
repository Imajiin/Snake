package pl.polsl.snake;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

// Nazwa klasy musi być Main, bo tak nazywa się Twój plik
public class Main extends AppCompatActivity {

    SnakeEngine snakeEngine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Prośba o uprawnienia do mikrofonu (Requirement: GŁOS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            }
        }

        // Pobieranie rozmiaru ekranu
        Display display = getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);

        // Inicjalizacja silnika
        snakeEngine = new SnakeEngine(this, size);
        setContentView(snakeEngine);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (snakeEngine != null) snakeEngine.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (snakeEngine != null) snakeEngine.resume();
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[]grantResults){
        super.onRequestPermissionsResult(requestCode,permissions, grantResults);
        if (requestCode == 1){
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                android.util.Log.d("SNAKE_VOICE", "Uprawnienia nadane");
            }else{

            }
        }
    }
}