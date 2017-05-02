package fr.rg.ignrando.util;


import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.StringTokenizer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import android.os.Bundle;
import android.text.format.Time;

/**
 * Lecture d'un fichier KML contenant un trajet (LineString) et, la plupart du
 * temps, des positions (avec leurs dates).
 *
 * Cette version retourne dans un 'Bundle': - le nom du fichier - la distance
 * cumulée - la liste des géolocalisations
 *
 * L'opération pouvant s'avérer longue, il est préférable de la mettre en oeuvre
 * dans le cadre d'une 'AsyncTask'.
 *
 * <p>
 * Format du fichier KML:
 *
 * <pre>
 * Document
 * |->name
 * |->description
 * |->Style id="track_n" // trace normale
 * | |-> IconStyle
 * | | |-> Icon
 * | | | |-> href
 * |->Style id="track_h" // trace surlignée
 * | |-> IconStyle
 * | | |-> scale
 * | | |-> Icon
 * | | | |-> href
 * |->StyleMap id="track"
 * | |-> Pair
 * | | |-> key
 * | | |-> styleUrl
 * | |-> Pair
 * | | |-> key
 * | | |-> styleUrl
 * |->Style id="lineStyle"
 * | |-> LineStyle
 * | | |-> color
 * | | |-> width
 * ///////// Points individuels (1 placemark par point) //////
 * |->Folder
 * | |->name
 * | |->Placemark
 * | | |-> Timestamp
 * | | | |-> when
 * | | |-> StyleUrl
 * | | |-> Point
 * | | | |-> coordinates
 * | | |-> description
 * | |->Placemark
 * | | |-> Timestamp
 * | | | |-> when
 * | | |-> StyleUrl
 * | | |-> Point
 * | | | |-> coordinates
 * | | |-> description
 * . . . . . .
 * | |->Placemark
 * | | |-> Timestamp
 * | | | |-> when
 * | | |-> StyleUrl
 * | | |-> Point
 * | | | |-> coordinates
 * | | |-> description
 * /////////// Points de la trace //////////////
 * |-> Placemark
 * | |-> name
 * | |-> styleUrl
 * | |-> LineString
 * | | |-> tessellate
 * | | |-> altitudeMode
 * | | |-> coordinates
 * </pre>
 */
public class KMLReader {
    public static final double RAYON_TERRE = 6370000; // 6'370 km

    // Tags du fichier KML
    private static final String KML_PLACEMARK = "Placemark";
    private static final String KML_WHEN = "when";
    private static final String KML_POINT = "Point";
    private static final String KML_COORDINATES = "coordinates";
    private static final String KML_LINESTRING = "LineString";

    // Clés d'enregistrement dans le bundle
    public static final String PATHNAME_KEY = "title_key"; // Nom de fichier
    public static final String LATMIN_KEY = "lat_min_key"; // Latitude minimale
    public static final String LATMAX_KEY = "lat_max_key"; // Latitude maximale
    public static final String LONGMIN_KEY = "long_min_key"; // Longitude minimale
    public static final String LONGMAX_KEY = "long_max_key"; // Longitude maximale
    public static final String LOCATIONS_KEY = "loc_list_key"; // liste de
    // positions
    public static final String SPEEDMIN_KEY = "vitesse_min_key"; // vitesse
    // minimale
    public static final String SPEEDMAX_KEY = "vitesse_max_key"; // vitesse
    // minimale

    /**
     * Cette méthode fait les hypothèses que:
     * <ul>
     * <li>Les positions apparaissent avant le trajet.</li>
     * <li>Il n'y a qu'un seul trajet.</li>
     * <li>Il n'existe pas 2 positions avec les même données de géolocalisation
     * (latitude,longitude,altitude).</li>
     * </ul>
     *
     * <p>
     * Le déroulement en une seule passe mais 2 étapes:
     *
     * <ol>
     * <li>Récupération de chaque position dans un noeud 'Placemark':
     * <ul>
     * <li>information de géolocalisation dans le sous-noeud 'coordinates',</li>
     * <li>information de date dans le sous-noeud 'when'. Chaque nouvelle position
     * est sauvegardée temporairement dans une HashMap où la clé est le triplet
     * (latitude,longitude,altitude).</li></li>
     * </ul>
     *
     * <li>Récupération de la trace dans le noeud 'LineString' (sous-noeud
     * 'Placemark'). A chaque nouveau point, on tenter de récupérer la position
     * correspondante dans la HashMap précédente, on calcule alors les
     * informations de vitesse et distance et on l'ajoute à la fin de la liste.</li>
     * </ol>
     *
     * @param fileName
     * @return
     */
    public static Bundle extractLocWithStAXCursor(String fileName) {
        Bundle bundle = new Bundle();

        ArrayList<GeoLocation> list = new ArrayList<GeoLocation>(); // Stockage du
        // trajet
        HashMap<String, GeoLocation> points = new HashMap<String, GeoLocation>(); // Stockage
        // des
        // positions
        String key = null; // Clé de stockage des positions

        XmlPullParserFactory xmlpf = null;
        int eventType;
        boolean inPoint = false;
        boolean inLineString = false;

        double longMin = 180;
        double longMax = -180;
        double latMin = 90;
        double latMax = -90;
        // float altMin = 100000;
        // float altMax = 0;
        float cumulDist = 0.0f;
        float vitMin = 10000;
        float vitMax = 0;
        GeoLocation g = null;
        Time time = new Time();
        String[] coord;
        int nbCollision = 0, nbCollisionMax = 0;
        double distance = 0;
        long duree = 0;

        // Initialisation du Bundle vide
        bundle.putString(PATHNAME_KEY, fileName); // Nom du fichier

        try {
            xmlpf = XmlPullParserFactory.newInstance();
            xmlpf.setNamespaceAware(true);
            XmlPullParser xpp = xmlpf.newPullParser();
            xpp.setInput(new FileReader(fileName));

            eventType = xpp.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) { // Jusqu'à la fin du
                // document

                switch (eventType) {
                    // +-----------------+
                    // | Balise ouvrante |
                    // +-----------------+
                    case XmlPullParser.START_TAG:
                        if (xpp.getName().equalsIgnoreCase(KML_PLACEMARK)) {
                            g = new GeoLocation();
                        } else if (xpp.getName().equalsIgnoreCase(KML_POINT)) {
                            inPoint = true;
                        } else if (xpp.getName().equalsIgnoreCase(KML_COORDINATES)) {
                            if (inPoint) {
                                key = xpp.nextText();
                                coord = key.split(",");
                                g.longitude = Double.parseDouble(coord[0]);
                                g.latitude = Double.parseDouble(coord[1]);
                                if (coord.length >= 3) { // altitude
                                    g.gpsElevation = Float.parseFloat(coord[2]);
                                    g.barElevation = g.gpsElevation;
                                }
                            } else if (inLineString) {
                                // Remplir la liste
                                StringTokenizer st = new StringTokenizer(xpp.nextText());
                                while (st.hasMoreTokens()) {
                                    key = st.nextToken();

                                    // Gestion des collisions
                                    nbCollision = 0;
                                    while (nbCollision <= nbCollisionMax && points.get(key) == null) {
                                        key += "-";
                                        nbCollision++;
                                    }
                                    if (points.get(key) != null) { // Un point existe déjà
                                        // Reprendre la géolocalisation de ce point
                                        g = points.get(key);
                                        points.remove(key); // Le supprimer de la liste

                                        // Distance cumulative et vitesse
                                        if (list.size() > 1) {
                                            GeoLocation lastLocation = list.get(list.size() - 1);

                                            distance = computeDistance(g, lastLocation);
                                            duree = (g.timeStampS - lastLocation.timeStampS);

                                            // Distance cumulative
                                            cumulDist += distance;
                                            g.length = (int) cumulDist;

                                            // Vitesse v=dx/dt
                                            if (g.timeStampS - lastLocation.timeStampS > 0) {
                                                g.speed = (g.length - lastLocation.length) * 3.6f / duree;
                                            } else { // Cas où dt=0
                                                g.speed = 0;
                                            }
                                            if (g.speed < vitMin) {
                                                vitMin = g.speed;
                                            }
                                            if (g.speed > vitMax) {
                                                vitMax = g.speed;
                                            }
                                        } else {
                                            g.length = 0;
                                            g.speed = 0;
                                        }
                                    } else { // Le point n'existe pas -> il n'aura pas de timeStamp
                                        // Supprimer les éventuels '-' à la fin de la clé
                                        while (key.charAt(key.length() - 1) == '-') {
                                            key = key.substring(0, key.length() - 1);
                                        }

                                        coord = key.split(",");
                                        g = new GeoLocation();
                                        g.longitude = Double.parseDouble(coord[0]);
                                        g.latitude = Double.parseDouble(coord[1]);
                                        if (coord.length >= 3) { // altitude
                                            g.gpsElevation = Float.parseFloat(coord[2]);
                                            g.barElevation = g.gpsElevation;
                                        }
                                    }

                                    // MàJ des extrema
                                    if (g.latitude < latMin) {
                                        latMin = g.latitude;
                                    }
                                    if (g.latitude > latMax) {
                                        latMax = g.latitude;
                                    }
                                    if (g.longitude < longMin) {
                                        longMin = g.longitude;
                                    }
                                    if (g.longitude > longMax) {
                                        longMax = g.longitude;
                                    }
                                    // if (g.gpsElevation < altMin) {
                                    // altMin = g.gpsElevation;
                                    // }
                                    // if (g.gpsElevation > altMax) {
                                    // altMax = g.gpsElevation;
                                    // }

                                    list.add(g); // Conserver la géolocalisation
                                    distance = 0;
                                    duree = 0;
                                }
                                g = null;
                            }
                        } else if (xpp.getName().equalsIgnoreCase(KML_WHEN)) {
                            if (g == null)
                                break;

                            String instant = xpp.nextText();
                            time.parse3339(instant);
                            g.timeStampS = time.normalize(false) / 1000; // en secondes
                        } else if (xpp.getName().equalsIgnoreCase(KML_LINESTRING)) {
                            inLineString = true;
                        }
                        break;
                    // +-----------------+
                    // | Balise fermante |
                    // +-----------------+
                    case XmlPullParser.END_TAG:
                        if (xpp.getName().equalsIgnoreCase(KML_PLACEMARK)) {
                            if (!inPoint) {
                                break;
                            }
                            inPoint = false;

                            if (g == null) {
                                break;
                            }

                            if (key != null) {
                                // Vérifier une éventuelle collision
                                while (points.get(key) != null) {
                                    key += "-";
                                    nbCollision++;
                                }
                                points.put(key, g);
                                if (nbCollision > nbCollisionMax) {
                                    nbCollisionMax = nbCollision;
                                }
                            }
                            g = null;
                        } else if (xpp.getName().equalsIgnoreCase(KML_LINESTRING)) {
                            inLineString = false;
                        }
                        break;
                }

                eventType = xpp.next();
            } // Traitement du document
        } catch (XmlPullParserException e) {
            e.printStackTrace();
        } catch (FileNotFoundException e2) {

        } catch (IOException e3) {

        } finally {

        }
        if (list.size() > 0) {
            // bundle.putInt(ALT_MAX_KEY, (int)altMax);
            // bundle.putInt(ALT_MIN_KEY, (int)altMin);
            bundle.putDouble(LATMIN_KEY, latMin);
            bundle.putDouble(LATMAX_KEY, latMax);
            bundle.putDouble(LONGMIN_KEY, longMin);
            bundle.putDouble(LONGMAX_KEY, longMax);
            bundle.putFloat(SPEEDMIN_KEY, vitMin);
            bundle.putFloat(SPEEDMAX_KEY, vitMax);
            bundle.putParcelableArrayList(LOCATIONS_KEY, list); // liste de positions
        }

        return bundle;
    }

    /**
     * Calcul le cosinus d'un angle donné en degrés.
     *
     * @param angledeg
     *          angle en degrés.
     * @return cosinus de l'angle.
     */
    private static double cos(double angledeg) {
        return Math.cos(Math.toRadians(angledeg));
    }

    /**
     * Calcul le sinus d'un angle donné en degrés.
     *
     * @param angledeg
     *          angle en degrés.
     * @return sinus de l'angle.
     */
    private static double sin(double angledeg) {
        return Math.sin(Math.toRadians(angledeg));
    }

    /**
     * Calcul de la distance séparant 2 géolocalisation (la formule utilisée prend
     * en compte l'altitude).
     *
     * @param g
     *          Position finale
     * @param lastLocation
     *          Position initiale
     * @return
     */
    public static double computeDistance(GeoLocation g, GeoLocation lastLocation) {
        double la = g.latitude;
        double lb = lastLocation.latitude;
        double da = g.longitude;
        double db = lastLocation.longitude;
        double ha = RAYON_TERRE + g.dispElevation;
        double hb = RAYON_TERRE + lastLocation.dispElevation;
        double dist = ha * ha + hb * hb - 2 * ha * hb
                * (cos(la) * cos(lb) + cos(da - db) * sin(la) * sin(lb));
        if (dist < 0) { // Cas où les calculs approximatifs empêchent de trouver 0
            return 0;
        } else {
            return Math.sqrt(dist);
        }
    }

}