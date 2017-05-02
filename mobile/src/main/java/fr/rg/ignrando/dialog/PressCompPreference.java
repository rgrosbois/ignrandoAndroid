package fr.rg.ignrando.dialog;


import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.preference.EditTextPreference;
import android.util.AttributeSet;

import fr.rg.ignrando.MainActivity;

/**
 * Compensation en altitude de l'altimètre utilisant le capteur de pression.
 * L'utilisateur fourni l'altitude réelle du lieu où il se trouve. Cette
 * altitude permet de calculer une valeur de compensation qui est sauvegardée
 * dans les préférences de l'application.
 *
 * <p>
 * Cette classe hérite de EditTextPreference afin
 *
 * @see EditTextPreference
 */
public class PressCompPreference extends EditTextPreference implements
        SensorEventListener {
    private Sensor pressureSensor;
    private int altitude;

    public PressCompPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        SensorManager manager = (SensorManager) context
                .getSystemService(MainActivity.SENSOR_SERVICE);
        pressureSensor = manager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        if (pressureSensor != null) { // Réactiver le capteur de pression
            manager.registerListener(this, pressureSensor, 2000); // toutes les 2s
        }
    }

    /**
     * Récupérer la dernière compensation d'altitude sauvegardée dans les
     * préférences et calculer l'altitude compensée.
     *
     * <p>
     * Cette méthode est appelée lors de l'affichage de la boîte de dialogue de
     * saisie de l'altitude réelle. Elle fournit une altitude compensée
     * à modifier par l'utilisateur.
     */
    @Override
    public String getText() {
        int compensation = Integer.parseInt(super.getText());
        return (altitude + compensation) + "";
    }

    /**
     * Enregistrement de la compensation d'altitude dans les préférences. La
     * compensation est calculée depuis l'altitude saisie par l'utilisateur et
     * celle fournie par le capteur de pression non compensé.
     */
    @Override
    public void setText(String text) {
        int newAltitude = Integer.parseInt(text);
        super.setText((newAltitude - altitude) + "");
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float pression = event.values[0];
        // Hypothèse de l'atmosphère standard: 15°C à 0m sous 1013.15 hPa
        // Gradient de température: -6.5°/1000m
        altitude = (int) (288.15 / 0.0065 * (1 - Math.pow(pression / 1013.25, 0.19)));
    }

}
