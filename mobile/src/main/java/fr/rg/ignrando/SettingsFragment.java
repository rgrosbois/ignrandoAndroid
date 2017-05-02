package fr.rg.ignrando;


import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.preference.PreferenceFragment;

public class SettingsFragment extends PreferenceFragment {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.main_preferences);

        // Gestion de l'altimètre si disponible
        SensorManager manager = (SensorManager)getActivity().
                getSystemService(MainActivity.SENSOR_SERVICE);
        if(manager.getDefaultSensor(Sensor.TYPE_PRESSURE)!=null) {
            addPreferencesFromResource(R.xml.elevation_preferences);
        }
    }

}