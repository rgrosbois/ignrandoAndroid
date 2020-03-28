package fr.rg.ignrando.util;

import android.os.Bundle;

import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.Polyline;

/**
 * Pour conserver une trace en mémoire:
 * <ul>
 * <li>ses données</li>
 * <li>ses éléments d'affichage</li>
 * <li>une éventuelle sous-trace (sélectionné sur le fragment d'information)</li>
 * </ul>
 */
public class Track {
    public Bundle b; // Bundle de données
    public Polyline plTrack, plSubTrack; // Polyline pour trace et sous-trace
    public Circle oneKmCirc;

    // Marqueurs de début, intermédiaire et fin de trace
    public Marker markStart, markInter, markEnd;
    public int iSubTrackStart = -1; // indice de début de sous-trace
    public int iSubTrackEnd = -1; // indice de fin de sous-trace
}
