package fr.rg.ignrando;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringTokenizer;

import android.app.Fragment;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.view.MotionEventCompat;
import android.text.Html;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import fr.rg.ignrando.util.GeoLocation;
import fr.rg.ignrando.util.KMLReader;

/**
 * Fragment permettant d'afficher les statistiques d'une trace ainsi qu'un
 * graphe mixte représentant:
 * <ul>
 * <li>la vitesse en fonction de la distance et du temps parcourus,</li>
 * <li>l'altitude en fonction de la distance et du temps parcourus.</li>
 * </ul>
 */
public class TrackInfoFragment extends Fragment {

    /** Couleurs utilisées. */
    private static final int transparentWhite = Color.argb(128, 255, 255, 255),
            transparantBlue = Color.argb(128, 0, 0, 255), transparentRed = Color
            .argb(128, 255, 0, 0), intervColor = Color.MAGENTA;

    // Liste des écouteurs de sélection de géolocalisation
    private final List<WeakReference<SubTrackSelectionListener>> gListeners = new ArrayList<WeakReference<SubTrackSelectionListener>>();

    /**
     * Distance cumulative (en m). Suite au traitement des données, cette valeur
     * peut différer de celle calculée à la lecture du fichier KML
     */
    private int distTot;
    /** Durée totale (avec les pause) de parcours de la trace. */
    private long dureeTot;
    /** Altitude maximale du graphe (en m) */
    private int graphElevMax;
    /** Altitude minimal du graphe (en m) */
    private int graphElevMin;
    /** Liste des géolocalisations */
    private ArrayList<GeoLocation> locList;
    /** Zone de dessin pour les courbes */
    private View graph;
    /** Vitesse minimale du graphe (en km/h). */
    private float graphSpeedMin;
    /** Vitesse maximale du graphe (en km/h). */
    private float graphSpeedMax;
    /** Pour l'affichage des statistiques de parcours */
    private TextView distanceLbl, elevLbl, denivLbl, durationLbl, speedLbl;

    // Utiliser ou non les altitudes issues du GPS (uniquement pour une
    // trace en cours d'enregistrement)
    private boolean elevSensorIsGPS = true;

    // Afficher les altitudes issues capteur (ou dans le fichier) ou celles
    // provenant du modèle 3D IGN.
    private boolean displaySensorElevation = true;

    // Indices de la géolocalisation sous les pointeurs.
    private int firstGeoLocIdx = -1, secondGeoLocIdx = -1;

    /**
     * Spécifier les indices des géolocalisations se trouvant sous les pointeurs.
     *
     * @param i1
     *          Indice correspondant au premier pointeur
     * @param i2
     *          Indice correspondant au deuxième pointeur
     */
    public void setSelectionIdx(int i1, int i2) {
        firstGeoLocIdx = i1;
        secondGeoLocIdx = i2;
        parseAndDisplayData(i1, i2);
    }

    /**
     * Créer un nouveau fragment d'information sur la trace. Les données de la
     * trace sous transférées à l'instance sous la forme d'un argument.
     *
     * @param pathData
     *          données du trajet
     * @return Fragment créé
     */
    public static TrackInfoFragment newInstance(Bundle pathData) {
        TrackInfoFragment frag = new TrackInfoFragment();
        frag.setArguments(pathData);
        return frag;
    }

    /**
     * Création du fragment avec récupération des données en tant qu'argument.
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        // Racine de l'arborescence de l'interface
        View view = inflater.inflate(R.layout.fragment_pathinfo, container, false);

        // Labels utilisés pour afficher les statistiques globales
        distanceLbl = (TextView) view.findViewById(R.id.distance_label);
        elevLbl = (TextView) view.findViewById(R.id.elev_label);
        denivLbl = (TextView) view.findViewById(R.id.deniv_label);
        durationLbl = (TextView) view.findViewById(R.id.duration_label);
        speedLbl = (TextView) view.findViewById(R.id.speed_label);

        // Graphe d'altitude et de vitesse
        graph = new MixedGraph(container.getContext());
        FrameLayout layout = (FrameLayout) view.findViewById(R.id.alti_layout);
        layout.addView(graph);

        // Menus du fragment
        // setHasOptionsMenu(true);

        // Paramètres d'affichage (pour une trace en cours d'enregistrement)
        SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(getActivity());
        elevSensorIsGPS = Integer
                .parseInt(prefs.getString("ELEVATION_SOURCE", "0")) == 0;
        displaySensorElevation = Integer.parseInt(prefs.getString(
                "ELEVATION_DISPLAY_TYPE", "0")) == 0;

        // Récupérer les éventuelles données de trace
        if (getArguments() != null) {
            addPath(getArguments());
        } else {
            clear();
        }

        return view;
    }

    ElevationCorrection elevCorr = null;

    /**
     * Spécifier la trace à analyser.
     *
     * @param b
     *          Données de la trace ou null pour effacer une trace existante
     */
    public void addPath(Bundle b) {
        if (b == null) { // Effacement d'une ancienne trace
            clear();
            return;
        }

        locList = b.getParcelableArrayList(KMLReader.LOCATIONS_KEY);
        if (locList != null && !locList.isEmpty()) {
            // Calculer la durée totale de la trace
            dureeTot = locList.get(locList.size() - 1).timeStampS
                    - locList.get(0).timeStampS;

            // Récupérer les altitudes issues d'un modèle de terrain (si nécessaire)
            if (!displaySensorElevation) {
                if (elevCorr != null) { // Tâche en cours
                    elevCorr.cancel(true);
                }
                elevCorr = new ElevationCorrection();
                elevCorr.execute(); // Lancer la tâche de correction
            } else {
                processData(); // traiter les données
                parseAndDisplayData(-1, -1); // analyser et afficher la trace entière
            }
        }

    }

    /**
     * Lisser et filtrer les données de géolocalisation. Extraire les extrema du
     * graphe mixte.
     */
    private void processData() {
        GeoLocation g, lastLocation = null;
        final float minDeltaAlt = 5;
        float cumulDist = 0;

        float pathSpeedMin = 1000;
        float pathSpeedMax = 0;
        int pathElevMin = 10000;
        int pathElevMax = 0;

        for (int i = 0; i < locList.size(); i++) {
            g = locList.get(i);

            // Récupérer la bonne altitude
            if (!displaySensorElevation) { // Utiliser l'altitude modélisée
                g.dispElevation = g.modelElevation;
            } else if (elevSensorIsGPS) { // Utiliser l'altitude du GPS
                g.dispElevation = g.gpsElevation;
            } else { // Utiliser l'altitude du baromètre
                g.dispElevation = g.barElevation;
            }

            if (lastLocation != null) {
                // Lisser l'altitude
                if (Math.abs(g.dispElevation - lastLocation.dispElevation) < minDeltaAlt) {
                    g.dispElevation = lastLocation.dispElevation;
                }

                // Recalculer la distance cumulative
                cumulDist += KMLReader.computeDistance(g, lastLocation);
                g.length = (int) cumulDist;

                // Recalculer la vitesse instantanée
                if (g.timeStampS - lastLocation.timeStampS > 0) {
                    g.speed = (g.length - lastLocation.length) * 3.6f
                            / (g.timeStampS - lastLocation.timeStampS);
                } else {
                    g.speed = 0;
                }

                // Attribuer une vitesse non nulle à la première position
                if (i == 1) {
                    lastLocation.speed = g.speed;
                }
            }

            // Mettre à jour les extrema
            if (g.speed < pathSpeedMin) {
                pathSpeedMin = g.speed;
            }
            if (g.speed > pathSpeedMax) {
                pathSpeedMax = g.speed;
            }
            if (g.dispElevation < pathElevMin) {
                pathElevMin = (int) g.dispElevation;
            }
            if (g.dispElevation > pathElevMax) {
                pathElevMax = (int) g.dispElevation;
            }

            lastLocation = g;
            // Log.d(MainActivity.DEBUG_TAG, "-> "+lastLocation);
        }
        // Modifier les variables globales
        distTot = (int) cumulDist;
        graphElevMax = pathElevMax + 100;
        graphElevMin = (pathElevMin / 1000) * 1000;
        graphSpeedMax = pathSpeedMax + 10;
        graphSpeedMin = 0;
        // Log.d(MainActivity.DEBUG_TAG,
        // "distTot="+distTot+", graphElevMax="+graphElevMax+", graphElevMin="+graphElevMin+
        // ", graphSpeedMax="+graphSpeedMax+",graphSpeedMin="+graphSpeedMin);
    }

    /**
     * Calculer les statistiques (locales ou globales) de la trace puis mettre à
     * jour les affichages.
     */
    private void parseAndDisplayData(int start, int end) {
        // Log.d(MainActivity.DEBUG_TAG, "parseAndDisplayData " + start + " à " +
        // end);
        if (locList == null || locList.isEmpty()) {
            return;
        }

        // Extrema
        float subpathCumulDist = 0;
        int subpathElevMin = 10000;
        int subpathElevMax = 0;
        float denivCumulPos = 0;
        float denivCumulNeg = 0;

        // Comptabilisation des pauses
        final float seuilVitesse = 1.5f; // en km/h (calcul des pauses)
        boolean pauseDetectee = false;
        long debutPause = 0; // Instant de départ de la pause
        long dureePause = 0; // Durée de la pause
        long dureeTotale, dureeSansPause;

        // Indices des géolocalisation de départ et de fin
        int lstart = start, lend = end;
        if (start == -1) {
            lstart = 0;
        }
        if (end == -1) {
            lend = locList.size() - 1;
        }
        if (lend < lstart) { // échanger droite-gauche
            int tmp = lend;
            lend = lstart;
            lstart = tmp;
        }

        GeoLocation lastLocation = null, g;
        for (int i = lstart; i <= lend; i++) { // Boucle sur les géolocalisations
            g = locList.get(i);

            if (lastLocation != null) {
                // Dénivelés cumulatifs
                if (g.dispElevation > lastLocation.dispElevation) { // Dénivelé positif
                    denivCumulPos += (g.dispElevation - lastLocation.dispElevation);
                } else { // Dénivelé négatif
                    denivCumulNeg += (lastLocation.dispElevation - g.dispElevation);
                }

                // Distance cumulative
                subpathCumulDist += KMLReader.computeDistance(g, lastLocation);
            }

            // Détection des pauses
            if (g.speed < seuilVitesse) { // Pas de mouvement
                if (!pauseDetectee) { // Début de pause
                    debutPause = g.timeStampS; // sauvegarder l'instant de départ
                    pauseDetectee = true;
                }
            } else { // Mouvement détecté
                if (pauseDetectee) { // Fin de pause
                    dureePause += (g.timeStampS - debutPause); // Calcul de la durée
                    pauseDetectee = false;
                }
            }

            // Extrema
            if (g.dispElevation < subpathElevMin) {
                subpathElevMin = (int) g.dispElevation;
            }
            if (g.dispElevation > subpathElevMax) {
                subpathElevMax = (int) g.dispElevation;
            }

            lastLocation = g;
        } // Fin de boucle sur les géolocalisations

        // Durées de parcours
        dureeTotale = locList.get(lend).timeStampS - locList.get(lstart).timeStampS;
        dureeSansPause = dureeTotale - dureePause;

        // +---------------------------+
        // | Afficher les statistiques |
        // +---------------------------+
        if (start == -1 && end == -1) { // Parcours complet -> couleur blanche
            distanceLbl.setTextColor(Color.WHITE);
            elevLbl.setTextColor(Color.WHITE);
            denivLbl.setTextColor(Color.WHITE);
            durationLbl.setTextColor(Color.WHITE);
            speedLbl.setTextColor(Color.WHITE);
        } else { // Sous-parcours -> couleur rougeatre
            distanceLbl.setTextColor(intervColor);
            elevLbl.setTextColor(intervColor);
            denivLbl.setTextColor(intervColor);
            durationLbl.setTextColor(intervColor);
            speedLbl.setTextColor(intervColor);
        }
        distanceLbl.setText(Html.fromHtml("<html>" + dist2String(subpathCumulDist)
                + "</html>"));
        elevLbl.setText(Html.fromHtml("<html><i><small>"
                + elev2HTML(subpathElevMin, subpathElevMax) + "</small></i></html>"));
        denivLbl.setText(Html.fromHtml("<html><i><small>"
                + deniv2HTML(denivCumulPos, denivCumulNeg) + "<small></i></html>"));
        durationLbl.setText(Html.fromHtml("<html><i><small>"
                + time2String(dureeTotale, true) + "<br>("
                + time2String(dureeSansPause, true) + ")" + "</small></i></html>"));
        speedLbl.setText(Html.fromHtml("<html><i><small>"
                + meanSpeeds2HTML(subpathCumulDist, dureeTotale, dureeSansPause)
                + "</small></i></html>"));

        if (graph != null) {
            graph.invalidate();
        }
    }

    /**
     * Supprimer la trace en mémoire et redessiner le graphe.
     */
    public void clear() {
        locList = null;
        if (graph != null) { // Redessiner le graphe
            graph.invalidate();
        }

        // Réinitialiser les affichages
        String emptyTxt = getString(R.string.empty_text);
        distanceLbl.setText(Html.fromHtml("<html>" + emptyTxt + "</html>"));
        denivLbl.setText(Html.fromHtml("<html><i><small>" + emptyTxt + "<br>"
                + emptyTxt + "</small></i></html>"));
        elevLbl.setText(Html.fromHtml("<html><i><small>" + emptyTxt + "<br>"
                + emptyTxt + "</small></i></html>"));
        durationLbl.setText(Html.fromHtml("<html><i><small>" + emptyTxt + "<br>"
                + emptyTxt + "</small></i></html>"));
        speedLbl.setText(Html.fromHtml("<html><i><small>" + emptyTxt + "<br>"
                + emptyTxt + "</small></i></html>"));
    }

    /**
     * Chaîne représentant une distance avec l'unité adéquate.
     *
     * @param distance
     *          Valeur de distance
     * @return Distance en mètres (si inférieure à 1km) ou kilomètre sinon.
     */
    public static String dist2String(float distance) {
        if (distance < 1000) { // Moins d'1km -> afficher en mètre
            return String.format(Locale.getDefault(), "%dm", (int) distance);
        } else { // Afficher en kilomètres
            return String.format(Locale.getDefault(), "%.1fkm", distance / 1000f);
        }
    }

    /**
     * Renvoie une chaîne représentant une durée au format __h__mn__s. Retourne
     * "-" dans le cas où la durée est nulle et l'affichage des secondes non
     * demandé.
     *
     * @param showSeconds
     *          Faire apparaître ou non les secondes dans la chaîne
     * @return
     */
    public static String time2String(long t, boolean showSeconds) {
        String s = "";

        if (t > 3600) { // Nombre d'heures
            s += String.format(Locale.getDefault(), "%dh", t / 60 / 60);
        }

        if (t % 3600 > 60) { // Quantité de minutes
            s += String.format(Locale.getDefault(), "%dmn", (t % 3600) / 60);
        }

        if (showSeconds && t % 60 != 0) { // Quantité de secondes
            s += String.format(Locale.getDefault(), "%ds", t % 60);
        }

        if (s == "") {
            s = "-";
        }

        return s;
    }

    /**
     * Chaîne contenant les résultats de calculs de vitesse moyennes avec ou sans
     * pause en km/h.
     *
     * @param distanceTot
     *          Distance totale de la trace en mètres.
     * @param dureeTotale
     *          Durée de la trace avec les pauses.
     * @param dureeSansPause
     *          Durée de la trace sans les pause.
     * @return vitesses en km/h au format HTML
     */
    private String meanSpeeds2HTML(float distanceTot, long dureeTotale,
                                   long dureeSansPause) {
        if (dureeTotale == 0) {
            return getString(R.string.empty_text);
        } else {
            double moyenne = (dureeTotale == 0) ? 0
                    : (distanceTot * 3.6 / dureeTotale);
            double moyenne2 = (dureeSansPause == 0) ? 0
                    : (distanceTot * 3.6 / dureeSansPause);
            return String.format(Locale.getDefault(), "%.1fkm/h<br>(%.1fkm/h)",
                    moyenne, moyenne2);
        }
    }

    /**
     * Chaîne contenant les dénivelés au format HTML.
     *
     * @param denivCumulPos
     *          Dénivelé positif en mètres
     * @param denivCumulNeg
     *          Dénivelé négatif en mètres
     *
     * @return chaîne au format HTML
     */
    private String deniv2HTML(float denivCumulPos, float denivCumulNeg) {
        return String.format(Locale.getDefault(), "+%dm<br>-%dm",
                (int) denivCumulPos, (int) denivCumulNeg);
    }

    /**
     * Chaîne contenant les extrema d'altitude ainsi que leur différence.
     *
     * @param pathElevMin
     *          Altitude minimale
     * @param pathElevMax
     *          Altitude maximale
     * @return chaîne au format HTML
     */
    private String elev2HTML(int pathElevMin, int pathElevMax) {
        return String.format(Locale.getDefault(), "%dm /%dm<br>(%dm)", pathElevMin,
                pathElevMax, pathElevMax - pathElevMin);
    }

    class MixedGraph extends View {

        // Constantes
        private final int BASE_MARGIN = 15;
        private final float INNER_SEP = 5;
        private final int XSEP = 20;
        private final int YSEP = 20;

        /** Courbe d'altitude. */
        private final Path pElev = new Path();
        /** Courbe de vitesse. */
        private final Path pSpeed = new Path();

        // Pinceaux
        private final Paint pElevFill, pSpeedFill, pElevCurve, pSpeedCurve,
                pDistLegend, pTimeLegend, pBackground, pElevLegend, pSpeedLegend,
                pInterv1, pIntervalle;

        /** Dimensions de la zone de dessin. */
        private int width, height;
        /** Coordonnées extrêmes du graphe (en pixels) */
        private int xMin, xMax, yMax, yMin;

        // Variables temporaires
        private final PointF c;
        private final Rect bounds = new Rect();
        private GeoLocation g;
        private String tmpS;

        /**
         * Constructeur.
         *
         * @param context
         */
        public MixedGraph(Context context) {
            super(context);

            DisplayMetrics metrics = new DisplayMetrics();
            WindowManager windowManager = (WindowManager) context
                    .getSystemService(Context.WINDOW_SERVICE);
            windowManager.getDefaultDisplay().getMetrics(metrics);

            pBackground = new Paint();
            pBackground.setColor(Color.WHITE);
            pBackground.setStyle(Paint.Style.FILL);

            pElevFill = new Paint();
            pElevFill.setDither(true);
            pElevFill.setColor(Color.RED);
            pElevFill.setStyle(Paint.Style.FILL);
            pElevFill.setStrokeWidth(2.0f);

            pSpeedFill = new Paint();
            pSpeedFill.setDither(true);
            pSpeedFill.setColor(Color.BLUE);
            pSpeedFill.setStyle(Paint.Style.FILL);
            pElevFill.setStrokeWidth(2.0f);

            pElevCurve = new Paint();
            pElevCurve.setColor(Color.rgb(255, 100, 100));
            pElevCurve.setStyle(Paint.Style.STROKE);
            pElevCurve.setTextSize(12 * metrics.scaledDensity);
            pElevCurve.setAntiAlias(true);
            pElevCurve.setFakeBoldText(true);

            pSpeedCurve = new Paint();
            pSpeedCurve.setColor(Color.rgb(100, 100, 255));
            pSpeedCurve.setStyle(Paint.Style.STROKE);
            pSpeedCurve.setTextSize(12 * metrics.scaledDensity);
            pSpeedCurve.setAntiAlias(true);
            pSpeedCurve.setFakeBoldText(true);

            pTimeLegend = new Paint();
            pTimeLegend.setColor(Color.GRAY);
            pTimeLegend.setStyle(Paint.Style.STROKE);
            pTimeLegend.setTextSize(12 * metrics.scaledDensity);
            pTimeLegend.setAntiAlias(true);
            pTimeLegend.setFakeBoldText(true);
            pTimeLegend.setPathEffect(new DashPathEffect(new float[] { 5, 5 }, 0));

            pDistLegend = new Paint(pTimeLegend);

            pElevLegend = new Paint(pDistLegend);
            pElevLegend.setColor(Color.rgb(255, 100, 100));

            pSpeedLegend = new Paint(pElevLegend);
            pSpeedLegend.setColor(Color.rgb(100, 100, 255));

            pInterv1 = new Paint();
            pInterv1.setStyle(Paint.Style.STROKE);
            pInterv1.setStrokeWidth(2);
            pInterv1.setColor(intervColor); // Magenta

            pIntervalle = new Paint();
            pIntervalle.setStyle(Paint.Style.FILL_AND_STROKE);
            pIntervalle.setStrokeWidth(2);
            pIntervalle.setColor(intervColor + (128 << 24)); // Magenta transparent

            c = new PointF();
        }

        private int mainPointerId = -1;
        private int pointerIdx = -1;

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = MotionEventCompat.getActionMasked(event);
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    // Récupérer l'index du pointeur primaire
                    mainPointerId = event.getPointerId(0);
                    performClick();
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_MOVE:
                    // Position du pointeur primaire
                    pointerIdx = MotionEventCompat.findPointerIndex(event, mainPointerId);
                    if (pointerIdx != -1) {
                        float x = MotionEventCompat.getX(event, pointerIdx);
                        float y = MotionEventCompat.getY(event, pointerIdx);
                        if (isInsideGraph(x, y)) {
                            firstGeoLocIdx = getNearestGeoLocIdx(x);
                        } else {
                            firstGeoLocIdx = -1;
                            parseAndDisplayData(-1, -1);
                        }
                    }
                    // Position du pointeur secondaire
                    secondGeoLocIdx = firstGeoLocIdx;
                    if (MotionEventCompat.getPointerCount(event) > 1) {
                        if (pointerIdx == 0) {
                            pointerIdx++;
                        } else {
                            pointerIdx = 0;
                        }
                        float x = MotionEventCompat.getX(event, pointerIdx);
                        float y = MotionEventCompat.getY(event, pointerIdx);

                        if (isInsideGraph(x, y)) {
                            secondGeoLocIdx = getNearestGeoLocIdx(x);
                            if (secondGeoLocIdx < firstGeoLocIdx) {
                                // Pointeur primaire à droite du 2nd -> échanger les indices
                                int tmp = firstGeoLocIdx;
                                firstGeoLocIdx = secondGeoLocIdx;
                                secondGeoLocIdx = tmp;
                            }
                            parseAndDisplayData(firstGeoLocIdx, secondGeoLocIdx);
                        } else { // 2ème Pointeur en dehors du graphe
                            secondGeoLocIdx = -1;
                            parseAndDisplayData(-1, -1);
                        }
                    }
                    // Avertir les écouteurs
                    for (WeakReference<SubTrackSelectionListener> wg : gListeners) {
                        SubTrackSelectionListener gsl = wg.get();
                        if (gsl != null) {
                            gsl.onGeoLocIntervalSeleted(firstGeoLocIdx, secondGeoLocIdx);
                        }
                    }
                    invalidate(xMin, yMin, xMax, yMax);
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        /**
         * Déterminer si la souris se trouve actuellement à l'intérieur du graphe ou
         * non.
         *
         * @param mx
         *          Abscisse informatique de la souris
         * @param my
         *          Ordonnée informatique de la souris
         * @return
         */
        private boolean isInsideGraph(float mx, float my) {
            return mx >= (xMin + XSEP) && mx <= (xMax - XSEP) && my >= (yMin + YSEP)
                    && my <= (yMax - YSEP);
        }

        /**
         * Déterminer la géolocalisation la plus proche de l'endroit pointé.
         *
         * @param x
         *          abscisse du pointeur
         * @return indice de la géolocalisation dans la trace
         */
        private int getNearestGeoLocIdx(float x) {
            int gIdx;

            if (locList == null || locList.isEmpty()) {
                return -1;
            }

            int dist = x2dist(x);
            // Trouver la géolocalisation la plus proche par dichotomie
            int imin = 0, imax = locList.size();
            gIdx = 0;
            boolean again = true;
            while (again) {
                gIdx = (imax + imin) / 2;
                if (gIdx == imax || gIdx == imin) {
                    again = false; // Géolocalisation trouvée
                } else if (locList.get(gIdx).length == dist) {
                    again = false; // Géolocalisation trouvée
                } else if (locList.get(gIdx).length < dist) {
                    // Chercher la géolocalisation dans la partie supérieure
                    imin = gIdx;
                } else {
                    // Chercher la géolocalisation dans la partie inférieure
                    imax = gIdx;
                }
            }
            return gIdx;
        }

        /**
         * Donner les coordonnées informatiques d'un point du graphe d'altitude. Le
         * résultat est renvoyé dans le premier paramètre afin de permettre
         * l'utilisation de la même variable Point2D à chaque appel.
         *
         * @param c
         *          Coordonnées informatiques
         * @param d
         *          Distance en mètres
         * @param alt
         *          Altitude en mètres
         */
        private void elevDist2xy(PointF c, int d, float alt) {
            c.x = dist2x(d);
            c.y = elev2y(alt);
        }

        /**
         * Calculer l'abscisse informatique d'une distance.
         *
         * @param d
         *          Distance en mètres
         * @return Abscisse en pixels
         */
        private float dist2x(int d) {
            return xMin + XSEP + d * 1.0f * (xMax - xMin - 2 * XSEP) / distTot;
        }

        /**
         * Calculer la distance parcourue correspondant à une abscisse informatique.
         *
         * @param x
         * @return
         */
        private int x2dist(float x) {
            return (int) ((x - xMin - XSEP) * distTot / (xMax - xMin - 2 * XSEP));
        }

        /**
         * Calcule l'ordonnée informatique d'une altitude.
         *
         * @param alt
         *          Altitude en mètres
         * @return Ordonnée en pixels
         */
        private float elev2y(float alt) {
            return yMax - YSEP - (alt - graphElevMin) * (yMax - yMin - 2 * YSEP)
                    / (graphElevMax - graphElevMin);
        }

        /**
         * Calculer les coordonnées informatiques d'un point du graphe de vitesse.
         *
         * @param c
         *          Coordonnées
         * @param d
         *          Distance en mètres
         * @param vit
         *          Vitesse en km/h
         */
        private void distSpeed2xy(PointF c, int d, float vit) {
            c.x = dist2x(d);
            c.y = speed2y(vit);
        }

        /**
         * Calculer l'ordonnée informatique d'une vitesse.
         *
         * @param speed
         *          Vitesse en km/h
         * @return Ordonnée dans la vue en pixels
         */
        private float speed2y(float speed) {
            return yMax - YSEP - (speed - graphSpeedMin) * (yMax - yMin - 2 * YSEP)
                    / (graphSpeedMax - graphSpeedMin);
        }

        /**
         * Dessiner les 2 graphes et afficher les statistiques de parcours dans la
         * vue
         */
        @Override
        public void draw(Canvas canvas) {
            super.draw(canvas);

            if (locList == null || locList.isEmpty()) {
                return;
            }
            int numPoints = locList.size();

            // +-------------------+
            // | Calcul des marges |
            // +-------------------+
            width = getWidth(); // Largeur de la vue
            height = getHeight(); // Hauteur de la vue

            // Marge gauche (légende d'altitude)
            tmpS = String.format(Locale.getDefault(), "%d m", graphElevMax);
            pElevCurve.getTextBounds(tmpS, 0, tmpS.length(), bounds);
            xMin = bounds.width() + BASE_MARGIN;

            // Marge droite (légende de vitesse)
            tmpS = String.format(Locale.getDefault(), "%.0f km/h", graphSpeedMax);
            pSpeedCurve.getTextBounds(tmpS, 0, tmpS.length(), bounds);
            xMax = width - bounds.width() - BASE_MARGIN;

            // Marges basse (axe distance) et haute (axe temporel)
            tmpS = "0 m";
            pDistLegend.getTextBounds(tmpS, 0, tmpS.length(), bounds);
            yMin = bounds.height() + BASE_MARGIN;
            yMax = height - yMin;

            // Colorier le fond en blanc
            canvas.drawRect(xMin, yMin, xMax, yMax, pBackground);

            // +-------------------+
            // | Courbe d'altitude |
            // +-------------------+
            // Créer le chemin correspondant à la courbe d'altitude
            // tout en identifiant l'ordonnée de l'altitude supérieure
            // pour le calcul du dégradé
            float yElevMax = yMax;
            pElev.reset();
            for (int i = 0; i < numPoints; i++) {
                g = locList.get(i);
                elevDist2xy(c, g.length, g.dispElevation);
                if (i == 0) {
                    pElev.moveTo(c.x, c.y);
                } else {
                    pElev.lineTo(c.x, c.y);
                }
                if (c.y < yElevMax) { // Max d'altitude = min d'ordonnée
                    yElevMax = c.y;
                }
            }
            // Dessiner la courbe (contour)
            canvas.drawPath(pElev, pElevCurve);
            // Fermer la courbe et dessiner le dégradé intérieur
            elevDist2xy(c, distTot, graphElevMin);
            pElev.lineTo(c.x, c.y);
            elevDist2xy(c, 0, graphElevMin);
            pElev.lineTo(c.x, c.y);
            pElev.close();
            pElevFill.setShader(new LinearGradient(0, yElevMax, 0, yMax,
                    transparentWhite, transparentRed, Shader.TileMode.CLAMP));
            canvas.drawPath(pElev, pElevFill);

            // +-------------------+
            // | Courbe de vitesse |
            // +-------------------+
            if (graphSpeedMax > 10000) {
                return; // Calcul de vitesse erroné (vitesse infinie = pb d'acquisition)
            }
            // Créer le chemin correspondant à la courbe de vitesse
            // tout en identifiant l'ordonnée de la vitesse maximale pour
            // le calcul du dégradé
            float ySpeedMax = yMax;
            pSpeed.reset();
            for (int i = 0; i < numPoints; i++) {
                g = locList.get(i);
                distSpeed2xy(c, g.length, g.speed);
                if (i == 0) {
                    pSpeed.moveTo(c.x, c.y);
                } else {
                    pSpeed.lineTo(c.x, c.y);
                }
                if (c.y < ySpeedMax) {
                    ySpeedMax = c.y;
                }
            }
            // Dessiner la courbe (contour)
            canvas.drawPath(pSpeed, pSpeedCurve);
            // Fermer la courbe et dessiner le dégradé intérieur
            distSpeed2xy(c, distTot, graphSpeedMin);
            pSpeed.lineTo(c.x, c.y);
            distSpeed2xy(c, 0, graphSpeedMin);
            pSpeed.lineTo(c.x, c.y);
            pSpeed.close();
            pSpeedFill.setShader(new LinearGradient(0, ySpeedMax, 0, yMax,
                    transparantBlue, transparentWhite, Shader.TileMode.CLAMP));
            canvas.drawPath(pSpeed, pSpeedFill);

            // +-------------------------+
            // | Graduations en altitude |
            // +-------------------------+
            int deniv = graphElevMax - graphElevMin;
            int incr;
            if (deniv < 1000) {
                incr = 100; // si la plage est inférieure à 1000 m => graduer tous les
                // 100 m
            } else {
                incr = 200; // Sinon graduer tous les 200 m
            }
            Path ap = new Path();
            for (int i = graphElevMin; i <= graphElevMax; i += incr) {
                elevDist2xy(c, 0, i);
                // Légende intermédiaire
                tmpS = String.format(Locale.getDefault(), "%dm", i);
                pElevLegend.getTextBounds(tmpS, 0, tmpS.length(), bounds);
                canvas.drawText(tmpS, xMin - INNER_SEP - bounds.width(),
                        c.y + bounds.height() / 2, pElevLegend);

                // Axe intermédiaire (traitillés)
                ap.reset();
                ap.moveTo(xMin, c.y);
                ap.lineTo(xMax - XSEP, c.y);
                canvas.drawPath(ap, pElevLegend);
            }

            if (!displaySensorElevation) {
                canvas.drawText("Mod.", 2, 20, pElevLegend);
            } else if (elevSensorIsGPS) {
                canvas.drawText("GPS", 2, 20, pElevLegend);
            } else {
                canvas.drawText("Bar.", 2, 20, pElevLegend);
            }

            // +------------------------+
            // | Graduations en vitesse |
            // +------------------------+
            // Les graduations dépendent de la plage de vitesse
            float deltaV = graphSpeedMax - graphSpeedMin;
            if (deltaV < 20) {
                incr = 2; // si la plage est inférieure à 20 km/h => graduer tous les 2
                // km/h
            } else {
                incr = 5; // Sinon graduer tous les 5 km/h
            }
            for (int i = (int) graphSpeedMin; i <= graphSpeedMax; i += incr) {
                distSpeed2xy(c, 0, i);
                // Légende intermédiaire
                tmpS = String.format(Locale.getDefault(), "%d km/h", i);
                pSpeedLegend.getTextBounds(tmpS, 0, tmpS.length(), bounds);
                canvas.drawText(tmpS, xMax + INNER_SEP, c.y + bounds.height() / 2,
                        pSpeedLegend);

                // Axe intermédiaire (traitillés)
                ap.reset();
                ap.moveTo(xMin + XSEP, c.y);
                ap.lineTo(xMax, c.y);
                canvas.drawPath(ap, pSpeedLegend);
            }

            // +-------------------------+
            // | Graduations de distance |
            // +-------------------------+
            if (distTot < 2000) {
                incr = 200; // Si moins de 2 km => graduer tous les 200 m
            } else if (distTot < 10000) {
                incr = 1000; // Si moins de 10 km => graduer tous les 1 km
            } else if (distTot < 20000) {
                incr = 2000; // Si moins de 20 km => graduer tous les 2 km
            } else {
                incr = 5000; // Sinon graduer tous les 5 km
            }
            for (int d = 0; d <= distTot; d += incr) {
                // Graduation verticale (traitillés)
                elevDist2xy(c, d, graphElevMin);
                ap.reset();
                ap.moveTo(c.x, yMin + YSEP);
                ap.lineTo(c.x, yMax + INNER_SEP / 2);
                canvas.drawPath(ap, pDistLegend);
                // Légende
                tmpS = String.format(Locale.getDefault(), "%dm", d);
                pDistLegend.getTextBounds(tmpS, 0, tmpS.length(), bounds);
                canvas.drawText(tmpS, c.x - bounds.width() / 2, yMax + INNER_SEP / 2
                        + bounds.height(), pDistLegend);
            }

            // +-------------------------+
            // | Graduations temporelles |
            // +-------------------------+
            if (numPoints > 1) {
                if (dureeTot < 60 * 30) { // Si moins de 30mn => graduer toutes les 5mn
                    incr = 5 * 60;
                } else if (dureeTot < 60 * 60) {// Si moins d'1h => graduer toutes les
                    // 10mn
                    incr = 10 * 60;
                } else if (dureeTot < 60 * 60 * 2) { // Si moins de 2h => graduer tous
                    // les
                    // 1/4h
                    incr = 15 * 60;
                } else { // Sinon graduer toutes les 30mn
                    incr = 30 * 60;
                }
                GeoLocation g0 = locList.get(0);
                int iLoc = 1;
                g = locList.get(iLoc); // Première position
                for (int t = incr; t < dureeTot; t += incr) {
                    // Trouver la géolocalisation se situant juste après la graduation
                    while (Math.abs(g.timeStampS - g0.timeStampS) < t) {
                        g = locList.get(++iLoc);
                    }

                    // Axe vertical (traitillés)
                    elevDist2xy(c, g.length, graphElevMax);
                    ap.reset();
                    ap.moveTo(c.x, yMin - INNER_SEP / 2);
                    ap.lineTo(c.x, yMax - YSEP);
                    canvas.drawPath(ap, pTimeLegend);
                    // Légende
                    tmpS = time2String(t, false);
                    pTimeLegend.getTextBounds(tmpS, 0, tmpS.length(), bounds);
                    canvas.drawText(tmpS, c.x - bounds.width() / 2,
                            yMin - bounds.height() / 2 - INNER_SEP / 2, pTimeLegend);
                }

                // Position particulière
                if (firstGeoLocIdx != -1) {
                    g = locList.get(firstGeoLocIdx);
                    elevDist2xy(c, g.length, g.dispElevation);
                    if (secondGeoLocIdx != -1) { // Intervalle de géolocalisations
                        GeoLocation gEnd = locList.get(secondGeoLocIdx);
                        PointF cEnd = new PointF();
                        elevDist2xy(cEnd, gEnd.length, gEnd.dispElevation);
                        // Rectangle
                        if (cEnd.x > c.x) {
                            canvas.drawRect(new RectF(c.x, yMin, cEnd.x, yMax), pIntervalle);
                        } else {
                            canvas.drawRect(new RectF(cEnd.x, yMin, c.x, yMax), pIntervalle);
                        }
                    }

                    // Ligne verticale
                    canvas.drawLine(c.x, yMin, c.x, yMax, pInterv1);
                    // Distance courante
                    tmpS = String.format(Locale.getDefault(), "%dm", g.length);
                    pDistLegend.getTextBounds(tmpS, 0, tmpS.length(), bounds);
                    RectF r = new RectF(c.x - bounds.width() / 2 - INNER_SEP, yMax
                            - bounds.height() - INNER_SEP, c.x + bounds.width() / 2
                            + INNER_SEP, yMax);
                    canvas.drawRect(r, pBackground);
                    canvas.drawRect(r, pInterv1);
                    canvas.drawText(tmpS, c.x - bounds.width() / 2, yMax - INNER_SEP,
                            pDistLegend);
                    // Durée courante
                    tmpS = time2String(g.timeStampS - g0.timeStampS, true);
                    pTimeLegend.getTextBounds(tmpS, 0, tmpS.length(), bounds);
                    r = new RectF(c.x - bounds.width() / 2 - INNER_SEP, yMin, c.x
                            + bounds.width() / 2 + INNER_SEP, yMin + bounds.height()
                            + INNER_SEP);
                    canvas.drawRect(r, pBackground);
                    canvas.drawRect(r, pInterv1);
                    canvas.drawText(tmpS, c.x - bounds.width() / 2,
                            yMin + bounds.height(), pDistLegend);
                    // Altitude
                    tmpS = ((int) g.dispElevation) + "m";
                    pElevLegend.getTextBounds(tmpS, 0, tmpS.length(), bounds);
                    r = new RectF(c.x - bounds.width() / 2 - INNER_SEP, c.y, c.x
                            + bounds.width() / 2 + INNER_SEP, c.y + bounds.height()
                            + INNER_SEP);
                    canvas.drawRect(r, pBackground);
                    canvas.drawRect(r, pElevCurve);
                    canvas.drawText(tmpS, c.x - bounds.width() / 2,
                            c.y + bounds.height(), pElevLegend);
                    // Vitesse
                    distSpeed2xy(c, g.length, g.speed);
                    tmpS = String.format(Locale.getDefault(), "%.1fkm/h", g.speed);
                    pSpeedLegend.getTextBounds(tmpS, 0, tmpS.length(), bounds);
                    r = new RectF(c.x - bounds.width() / 2 - INNER_SEP, c.y, c.x
                            + bounds.width() / 2 + INNER_SEP, c.y + bounds.height()
                            + INNER_SEP);
                    canvas.drawRect(r, pBackground);
                    canvas.drawRect(r, pSpeedCurve);
                    canvas.drawText(tmpS, c.x - bounds.width() / 2,
                            c.y + bounds.height(), pSpeedLegend);

                }
            }

        }
    }

    /**
     * Pour écouter une éventuelle sélection dans la trace.
     */
    public interface SubTrackSelectionListener {

        /**
         * Prévient l'écouteur qu'un sous-ensemble de la trace a été sélectionné.
         *
         * @param geoIdx1
         *          Index de la géolocalisation de départ.
         * @param geoIdx2
         *          Index de la géolocalisation d'arrivée.
         */
        public void onGeoLocIntervalSeleted(int geoIdx1, int geoIdx2);
    }

    public void addSubTrackSelectionListener(SubTrackSelectionListener gsl) {
        WeakReference<SubTrackSelectionListener> g = new WeakReference<SubTrackSelectionListener>(
                gsl);
        if (!gListeners.contains(g)) {
            gListeners.add(g);
        }
    }

    public void removeSubTrackSelectionListener(SubTrackSelectionListener gsl) {
        WeakReference<SubTrackSelectionListener> g = new WeakReference<SubTrackSelectionListener>(
                gsl);
        if (gListeners.contains(g)) {
            gListeners.remove(g);
        }
    }

    /**
     * Calcul des altitudes ramenées au niveau du sol pour chaque géolocalisation
     * de la trace à l'aide de requêtes IGN.
     */
    private class ElevationCorrection extends AsyncTask<Void, Float, Void> {
        // Nombre de géolocalisations à traiter en même temps afin de minimiser
        // les accès Internet
        private final int nbLocInQuery = 50;

        /**
         * Activer la barre de progression.
         */
        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            getActivity().setProgressBarVisibility(true);
            getActivity().getWindow().setFeatureInt(Window.FEATURE_PROGRESS,0);
        }

        @Override
        protected Void doInBackground(Void... voids) {
            Log.d(MainActivity.DEBUG_TAG, "*** Correction IGN ***");
            int[] glIndexes = new int[locList.size()];

            // Identifier le nombre d'altitudes à corriger et repérer les
            // indices de leurs géolocalisations
            int len = 0;
            GeoLocation g;
            for (int i = 0; i < locList.size(); i++) {
                g = locList.get(i);
                if (g.modelElevation == -1) {
                    glIndexes[len++] = i;
                }
            }
            Log.d(MainActivity.DEBUG_TAG, len + " altitude(s) à corriger");
            if (len == 0) { // Aucune altitude à corriger
                return null;
            }

            int j;
            double[] latitude = new double[nbLocInQuery];
            double[] longitude = new double[nbLocInQuery];
            int[] elevation = new int[nbLocInQuery];

            for (int i = 0; i < len; i += nbLocInQuery) {
                // Créer un groupe de géolocalisation
                for (j = 0; j < nbLocInQuery && i + j < len; j++) {
                    g = locList.get(glIndexes[i + j]);
                    latitude[j] = g.latitude;
                    longitude[j] = g.longitude;
                }
                // Récupérer les altitudes corrigées
                getQuickIGNElevations(latitude, longitude, elevation, j);

                // Recopier les altitudes corrigées
                for (j = 0; j < nbLocInQuery && i + j < len; j++) {
                    g = locList.get(glIndexes[i + j]);
                    g.modelElevation = elevation[j];
                    Log.d(MainActivity.DEBUG_TAG, " -> " + g);
                }
                publishProgress(new Float(i*1.0f/len));
            }

            return null;
        }

        @Override
        protected void onProgressUpdate(Float... values) {
            super.onProgressUpdate(values);
            getActivity().getWindow().setFeatureInt(Window.FEATURE_PROGRESS,
                    (int)(values[0]*10000));
        }

        /**
         * Désactiver la barre de progression.
         */
        @Override
        protected void onPostExecute(Void result) {
            getActivity().setProgressBarVisibility(false);
            processData(); // traiter les données
            parseAndDisplayData(-1, -1); // analyser et afficher la trace entière
            getActivity().setProgressBarVisibility(false);
            elevCorr = null; // supprimer la référence
            super.onPostExecute(result);
        }

        /**
         * Gestion des requêtes IGN pour l'obtention des altitudes corrigées.
         *
         * @param latitude
         *          tableau de latitudes des géolocalisations à corriger
         * @param longitude
         *          tableau de longitudes des géolocalisations à corriger
         * @param elevation
         *          tableau pour stocker les altitudes corrigées
         * @param nbLoc
         *          taille utile des tableaux
         */
        private void getQuickIGNElevations(double[] latitude, double[] longitude,
                                           int[] elevation, int nbLoc) {
            String urlString;
            URL url;
            HttpURLConnection urlConnection;
            BufferedReader in;

            // Récupérer la clé IGN
            SharedPreferences settings = PreferenceManager
                    .getDefaultSharedPreferences(getActivity());
            String cleIGN = settings.getString("IGN_DEVELOPMENT_KEY", "");

            urlString = "http://gpp3-wxs.ign.fr/" + cleIGN
                    + "/alti/rest/elevation.json?lat=" + latitude[0];
            for (int j = 1; j < nbLoc; j++) {
                urlString += "," + latitude[j];
            }
            urlString += "&lon=" + longitude[0];
            for (int j = 1; j < nbLoc; j++) {
                urlString += "," + longitude[j];
            }
            urlString += "&zonly=true&delimiter=,";
            // Log.d(MainActivity.DEBUG_TAG,"urlString="+urlString);
            try {
                url = new URL(urlString);
                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestProperty("Referer", "http://localhost/IGN/");

                // Lecture de la réponse, ligne par ligne.
                String reponse = "", line;
                in = new BufferedReader(new InputStreamReader(
                        urlConnection.getInputStream()));
                while ((line = in.readLine()) != null) {
                    reponse += line;
                }
                in.close();
                // Log.d(MainActivity.DEBUG_TAG,"reponse="+reponse);

                int startAltIdx, endAltIdx;
                startAltIdx = reponse.indexOf("[");
                endAltIdx = reponse.indexOf("]", startAltIdx + 1);
                StringTokenizer st = new StringTokenizer(reponse.substring(
                        startAltIdx + 1, endAltIdx), ",");
                int j = 0;
                while (st.hasMoreTokens()) {
                    elevation[j++] = (int) Float.parseFloat(st.nextToken());
                }
            } catch (IOException ex) {
                Log.e(MainActivity.DEBUG_TAG, "Erreur de communication avec le serveur");
            }
        }
    }

}