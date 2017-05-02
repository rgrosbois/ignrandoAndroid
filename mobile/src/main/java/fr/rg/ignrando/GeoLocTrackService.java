package fr.rg.ignrando;


import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.text.format.Time;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import fr.rg.ignrando.util.GeoLocation;
import fr.rg.ignrando.util.KMLReader;
import fr.rg.ignrando.util.KMLWriter;
import fr.rg.ignrando.util.WMTS;

/**
 * <p>Service permettant d'enregistrer et de chronométrer une trace GPS
 * grâce à l'écoute et la mémorisation des géolocalisations diffusées
 * par le GPS.</p>
 * <p>
 * <p>L'activité principale démarre ce service puis se connecte/déconnecte
 * en fonction de son cycle de vie.</p>
 * <p>
 * <p>Les géolocalisations sont stockées dans un Bundle qui est ensuite diffusé
 * aux écouteurs (ici l'activité principale).</p>
 * <p>
 * <p>Afin de s'affranchir du temps de synchronisation du GPS, l'écoute
 * des géolocalisations peut être activée indépendamment de la mémorisation.</p>
 * <p>
 * <p>L'enregistrement des géolocalisations peut être démarré, arrêté ou
 * annulé par n'importe quel client du service (ici l'activité principale de
 * l'application).</p>
 * <p>
 * <p>Le GPS fournit les informations de latitude, longitude, altitude et date.
 * Pour améliorer la précision des données, la date fournie par le GPS est
 * remplacée par celle du périphérique Android. En cas de présence d'un
 * altimètre sur le périphérique Android, la dernière altitude fournie par ce
 * dernier est mémorisée pour la position courante (en plus de celle
 * fournie par le GPS) et 2 traces sont générées en fin d'acquisition (une avec
 * les altitudes du GPS, l'autre avec celles de l'altimètre).</p>
 * <p>
 * <p>La trace est périodiquement sauvegardée sur la carte SD durant
 * l'enregistrement afin de ne pas tout perdre en cas de plantage de
 * l'application.</p>
 */
public class GeoLocTrackService extends Service implements LocationListener {

    /**
     * Dessin des images de notification
     */
    private Paint paint;

    private int updateNotificationId = 001;

    /**
     * États possibles de l'enregistrement des localisations: démarré,
     * arrêté ou en pause.
     */
    private enum RecordingState {
        STARTED, STOPPED, PAUSED
    }

    /**
     * État courant de l'enregistrement
     */
    private RecordingState rstate;

    /**
     * Message diffusé lors de l'acquisition d'une nouvelle géolocalisation
     */
    public static final String PATH_UPDATED = "path_updated";

    /**
     * Altimètre de l'appareil (capteur de pression)
     */
    private Sensor pressureSensor = null;

    /**
     * Dernière altitude fournie par l'altimètre
     */
    private int curElevBar;

    // Enregistrement des données
    private Bundle recordBundle = null;
    private long cumulTime; // Nb de secondes de l'enregistrement
    private Time time = new Time(); // pour récupérer l'heure courante
    private Timer saveTimer; // Sauvegarde périodique automatique
    private SavingTask periodicSavingTask;

    // Gestion de(s) client(s)
    private final MyBinder mBinder = new MyBinder();

    // Récupération des tuiles IGN pour notifications
    private IGNTileProvider tileProvider;

    /**
     * Écouter les données issues du capteur de pression (si disponible).
     * Le service est initialement à l'état RecordingState.STOPPED.
     */
    @Override
    public void onCreate() {
        super.onCreate();

        tileProvider = new IGNTileProvider(this);

        // Pour dessiner les images de la notification
        paint = new Paint();
        paint.setColor(Color.BLUE);
        paint.setStrokeWidth(3);

        // État initial
        rstate = RecordingState.STOPPED;

        // Altimètre (capteur de pression) si disponible
        SensorManager manager = (SensorManager) getSystemService(MainActivity.SENSOR_SERVICE);
        pressureSensor = manager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        if (pressureSensor != null) {
            ((SensorManager) getSystemService(MainActivity.SENSOR_SERVICE))
                    .registerListener(new SensorEventListener() {
                        @Override
                        public void onAccuracyChanged(Sensor sensor, int accuracy) {
                        }

                        /**
                         * Calcul de l'altitude brute depuis la pression sans compensation
                         */
                        @Override
                        public void onSensorChanged(SensorEvent event) {
                            float pression = event.values[0];
                            curElevBar = (int) (288.15 / 0.0065 * (1 - Math.pow(
                                    pression / 1013.25, 0.19)));
                        }
                    }, pressureSensor, 1000000); // toutes les 1s
        }
    }

    /**
     * Démarrage explicite du service (lors de la création de l'activité
     * principale) qui ne s'arrêtera qu'avec une demande explicite
     * (START_STICKY).
     *
     * @param intent
     * @param flags
     * @param startId
     * @return
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return Service.START_STICKY;
    }

    /**
     * Connexion au service (lorsque l'activité principale repasse en
     * premier plan).
     *
     * @param i
     * @return Instance de MyBinder pour gérer l'association
     */
    @Override
    public IBinder onBind(Intent i) {
        return mBinder;
    }

    /**
     * Démarrer un nouvel enregistrement:
     * <ul>
     * <li>(ré)initialiser le bundle,</li>
     * <li>définir le(s) nom(s) du(des) fichier(s) de sauvegarde.</li>
     * <li>changer l'état du service.</li>
     * </ul>
     */
    public void startRecording() {
        cumulTime = 0;

        // Création et initialisation du bundle
        recordBundle = new Bundle();
        recordBundle.putDouble(KMLReader.LATMIN_KEY, 90);
        recordBundle.putDouble(KMLReader.LATMAX_KEY, -90);
        recordBundle.putDouble(KMLReader.LONGMIN_KEY, 180);
        recordBundle.putDouble(KMLReader.LONGMAX_KEY, -180);
        recordBundle.putFloat(KMLReader.SPEEDMIN_KEY, 10000);
        recordBundle.putFloat(KMLReader.SPEEDMAX_KEY, 0);
        recordBundle.putParcelableArrayList(KMLReader.LOCATIONS_KEY,
                new ArrayList<GeoLocation>());

        // Nom de fichier pour l'enregistrement
        time.setToNow();
        recordBundle.putString(
                KMLReader.PATHNAME_KEY,
                new File(getApplicationContext().getExternalFilesDir(null), time
                        .format("%Y%m%d-%H%M.kml")).getAbsolutePath());

        // État du service
        rstate = RecordingState.STARTED;

        // Sauvegardes automatiques périodiques, toutes les 2mn
        saveTimer = new Timer("Automatic saving", true);
        long duree = 1000 * 60 * 2;
        periodicSavingTask = new SavingTask();
        saveTimer.scheduleAtFixedRate(periodicSavingTask, duree, duree);
    }

    /**
     * Annuler l'enregistrement.
     */
    public void cancelRecording() {
        rstate = RecordingState.STOPPED;
        // Arrêter les sauvegardes automatiques
        periodicSavingTask.purgeCache();
        saveTimer.cancel();
        saveTimer.purge();
        recordBundle = null;
    }

    /**
     * Mettre l'enregistrement en pause.
     */
    public void pauseRecording() {
        rstate = RecordingState.PAUSED;
    }

    /**
     * Terminer l'enregistrement.
     */
    public void stopRecording() {
        rstate = RecordingState.STOPPED;

        try { // Sauvegarder les données
            String name = recordBundle.getString(KMLReader.PATHNAME_KEY);
            FileOutputStream outputStream;

            if (pressureSensor == null) { // Avec altitudes issues du GPS
                outputStream = new FileOutputStream(new File(name));
                if (outputStream != null) {
                    KMLWriter.writeKMLFile(
                            recordBundle.getParcelableArrayList(KMLReader.LOCATIONS_KEY),
                            outputStream, true);
                }
            } else { // Avec altitudes barométriques
                int suffIdx = name.lastIndexOf('.');
                outputStream = new FileOutputStream(new File(
                        name.substring(0, suffIdx) + "-bar.kml"));
                if (outputStream != null) {
                    KMLWriter.writeKMLFile(
                            recordBundle.getParcelableArrayList(KMLReader.LOCATIONS_KEY),
                            outputStream, false);
                }
            }
        } catch (FileNotFoundException e) {
        }
        // Arrêter les sauvegardes automatiques
        saveTimer.cancel();
        saveTimer.purge();
        recordBundle = null;

        // Enlever les notifications
        NotificationManagerCompat notifManager = NotificationManagerCompat
                .from(this);
        notifManager.cancel(updateNotificationId);
    }

    /**
     * Démarrer la surveillance de la géolocalisation. Il est préférable d'appeler
     * cette méthode quelque temps avant le début de l'enregistrement afin
     * d'éliminer le temps de synchronisation du GPS.
     */
    public void startGeoLocationTracking() {
        LocationManager locManager = (LocationManager) getSystemService
                (Context.LOCATION_SERVICE);
        if (ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_COARSE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) {
            return;
        }
        locManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                5000, // 5s
                10, // 10m
                this);
    }

    /**
     * Arrêter la surveillance de la géolocalisation.
     */
    public void stopGeoLocationTracking() {
        LocationManager locManager = (LocationManager) getSystemService
                (Context.LOCATION_SERVICE);
        locManager.removeUpdates(this);
    }

    /**
     * En mode enregistrement, traiter la nouvelle géolocalisation:
     * <ul>
     * <li>Remplacer l'heure GPS par celle du système (plus précise, surtout en
     * début de synchronisation GPS).</li>
     * <li>Tenir à jour les extrema de latitude, longitude et altitude dans le
     * Bundle.</li>
     * <li>Calculer les durées et distances cumulatives.</li>
     * <li>Ajouter la géolocalisation à la liste.</li>
     * </ul>
     */
    public void onLocationChanged(Location newLoc) {
        double latMin, latMax, longMin, longMax;
        double distance;
        long duree;

        if (rstate != RecordingState.STARTED) {
            return; // pas d'enregistrement en cours
        }

        // Source insuffisamment précise pour cette application
        if (!newLoc.hasAltitude()) {
            return;
        }

        // Récupérer les géolocalisations précédentes
        // dans le bundle
        ArrayList<GeoLocation> locList = recordBundle
                .getParcelableArrayList(KMLReader.LOCATIONS_KEY);

        // Nouvelle position
        GeoLocation loc = new GeoLocation(newLoc);

        // Remplacer l'heure GPS par l'heure système
        time.setToNow();
        loc.timeStampS = time.toMillis(true) / 1000;

        // Sauvegarder l'altitude fournie par le baromètre (après correction)
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int elevationCorrection = Integer.parseInt(prefs.getString("ELEVATION_CORRECTION", "0"));
        curElevBar += elevationCorrection; // Compenser l'élévation
        loc.barElevation = curElevBar;

        // Mettre à jour les statistiques cumulatives
        if (locList.size() > 1) {
            GeoLocation lastLocation = locList.get(locList.size() - 1);
            distance = KMLReader.computeDistance(loc, lastLocation);
            duree = loc.timeStampS - lastLocation.timeStampS;

            // Déplacement non significatif ou suspicieux
            if (distance < 15 && duree < 60 || distance > 1000 && duree < 120) {
                return;
            }

            // Calculer la durée cumulative
            cumulTime += duree;

            // Distance cumulative
            loc.length = lastLocation.length + (int) distance;

            // Vitesse
            loc.speed = (loc.length - lastLocation.length) * 3.6f
                    / (loc.timeStampS - lastLocation.timeStampS);
            float vitMin = recordBundle.getFloat(KMLReader.SPEEDMIN_KEY);
            float vitMax = recordBundle.getFloat(KMLReader.SPEEDMIN_KEY);
            if (loc.speed < vitMin) {
                vitMin = loc.speed;
                recordBundle.putFloat(KMLReader.SPEEDMIN_KEY, vitMin);
            }
            if (loc.speed > vitMax) {
                vitMax = loc.speed;
                recordBundle.putFloat(KMLReader.SPEEDMAX_KEY, vitMax);
            }
        } else { // Vitesse et distance cumulatives nulle pour première position
            loc.speed = 0;
            loc.length = 0;
        }

        // Mettre à jour les extrema du bundle
        latMin = recordBundle.getDouble(KMLReader.LATMIN_KEY);
        if (loc.latitude < latMin) {
            latMin = loc.latitude;
            recordBundle.putDouble(KMLReader.LATMIN_KEY, latMin);
        }
        latMax = recordBundle.getDouble(KMLReader.LATMAX_KEY);
        if (loc.latitude > latMax) {
            latMax = loc.latitude;
            recordBundle.putDouble(KMLReader.LATMAX_KEY, latMax);
        }
        longMin = recordBundle.getDouble(KMLReader.LONGMIN_KEY);
        if (loc.longitude < longMin) {
            longMin = loc.longitude;
            recordBundle.putDouble(KMLReader.LONGMIN_KEY, longMin);
        }
        longMax = recordBundle.getDouble(KMLReader.LONGMAX_KEY);
        if (loc.longitude > longMax) {
            longMax = loc.longitude;
            recordBundle.putDouble(KMLReader.LONGMAX_KEY, longMax);
        }

        // Sauvegarder la géolocalisation dans le bundle
        locList.add(loc);

        // Prévenir les écouteurs
        sendBroadcast(new Intent(PATH_UPDATED));

        // Notification (notamment pour la montre)
        // temps écoulé + distance parcourue + altitude courante
        // + portions de carte IGN correspondantes
        if (thNotif != null) {
            thNotif.interrupt();
        }
        thNotif = new Thread(new Runnable() {
            @Override
            public void run() {
                sendNewLocNotification();
            }
        });
        thNotif.start();
    }

    private Thread thNotif;

    /**
     * Écouteur GPS
     *
     * @param s
     * @param i
     * @param bundle
     */
    @Override
    public void onStatusChanged(String s, int i, Bundle bundle) {

    }

    /**
     * Écouteur GPS
     *
     * @param s
     */
    @Override
    public void onProviderEnabled(String s) {

    }

    /**
     * Écouteur GPS
     *
     * @param s
     */
    @Override
    public void onProviderDisabled(String s) {

    }

    /**
     * Notifier les écouteurs (dont la montre Android Wear) qu'une nouvelle
     * géolocalisation a été prise en compte.
     * <p/>
     * La notification est constituée d'un texte indiquant la durée,
     * la distance et l'altitude ainsi que des portions de cartes IGN (niveau
     * 15 et 16) si disponibles dans le cache.
     */
    private void sendNewLocNotification() {
        ArrayList<GeoLocation> locList = recordBundle
                .getParcelableArrayList(KMLReader.LOCATIONS_KEY);

        // Message à afficher
        GeoLocation loc = locList.get(locList.size() - 1);
        String text = "tps=" + TrackInfoFragment.time2String(cumulTime, true)
                + ", dist=" + TrackInfoFragment.dist2String(loc.length) +
                ", alt=" + loc.gpsElevation + "m";

        // Créer la notification
        NotificationCompat.Builder notificationBuilder =
                new NotificationCompat.Builder(GeoLocTrackService.this)
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setContentTitle("Nouvelle position")
                        .setContentText(text);

        // Extension Android wear de la notification
        NotificationCompat.WearableExtender wearableExtender =
                new NotificationCompat.WearableExtender();

        // Tuile de niveau 15
        Bitmap img15 = getCenteredImage(15, true);
        if (img15 != null) {
            wearableExtender.setBackground(img15);
            Notification igm15Notif = new NotificationCompat
                    .Builder(this)
                    .extend(new NotificationCompat.WearableExtender()
                            .setBackground(img15).setHintShowBackgroundOnly
                                    (true))
                    .build();
            wearableExtender.addPage(igm15Notif);

        }

        // Tuile de niveau 16
        Bitmap img16 = getCenteredImage(16, true);
        if (img16 != null) {
            Notification igm16Notif = new NotificationCompat
                    .Builder(this)
                    .extend(new NotificationCompat.WearableExtender()
                            .setBackground(img16).setHintShowBackgroundOnly
                                    (true))
                    .build();
            wearableExtender.addPage(igm16Notif);
        }

        // Placer les notifications d'images
        notificationBuilder.extend(wearableExtender);

        // Récupérer le gestionnaire de notifications
        NotificationManagerCompat notificationManager =
                NotificationManagerCompat.from(GeoLocTrackService.this);

        // Envoyer la notification
        notificationManager.notify(updateNotificationId, notificationBuilder.build());
    }

    /**
     * Retourner une image de carte 256x256 contenant la géolocalisation
     * spécifiée en son centre.
     *
     * @param zoom niveau de zoom IGN
     * @param draw dessiner ou non la position sur l'image
     * @return L'image du cache modifiée ou null si elle ne s'y trouve pas
     */
    private Bitmap getCenteredImage(int zoom, boolean draw) {
        ArrayList<GeoLocation> locList = recordBundle
                .getParcelableArrayList(KMLReader.LOCATIONS_KEY);
        // Dernière position
        GeoLocation lastLoc = locList.get(locList.size() - 1);

        // Identifier la tuile contenant la géolocalisation
        // et les coordonnées de cette dernière
        int c = WMTS.longToTileCol(lastLoc.longitude, zoom);
        int r = WMTS.latToTileRow(lastLoc.latitude, zoom);
        double tileDim = WMTS.getTileDim(zoom);
        double mapOrigWmtsX = c * 256;
        double mapOrigWmtsY = r * 256;
        float x = (float) (WMTS.longToWmtsX(lastLoc.longitude)
                / tileDim * 256 - mapOrigWmtsX);
        float y = (float) (WMTS.latToWmtsY(lastLoc.latitude)
                / tileDim * 256 - mapOrigWmtsY);

        // Identifier les 4 tuiles entourant la position courante
        Bitmap[][] tileImg = new Bitmap[2][2];
        byte[] tileBytes;
        if (x <= 128) { // Tuiles de droite
            mapOrigWmtsX -= 256;
            x += 256;
            if (y <= 128) { // Tuile inférieure droite
                mapOrigWmtsY -= 256;
                y += 256;
                tileBytes = tileProvider.readRealTileImage(c, r, zoom);
                if (tileBytes != null && tileBytes.length != 0) {
                    tileImg[1][1] = BitmapFactory.decodeByteArray(tileBytes, 0,
                            tileBytes.length);
                }
                // Les 3 autres
                tileBytes = tileProvider.readRealTileImage(c - 1, r, zoom);
                if (tileBytes != null && tileBytes.length != 0) {
                    tileImg[1][0] = BitmapFactory.decodeByteArray(tileBytes, 0,
                            tileBytes.length);
                }
                tileBytes = tileProvider.readRealTileImage(c, r - 1, zoom);
                if (tileBytes != null && tileBytes.length != 0) {
                    tileImg[0][1] = BitmapFactory.decodeByteArray(tileBytes, 0,
                            tileBytes.length);
                }
                tileBytes = tileProvider.readRealTileImage(c - 1, r - 1, zoom);
                if (tileBytes != null && tileBytes.length != 0) {
                    tileImg[0][0] = BitmapFactory.decodeByteArray(tileBytes, 0,
                            tileBytes.length);
                }
            } else { // Tuile supérieure droite
                tileBytes = tileProvider.readRealTileImage(c, r, zoom);
                if (tileBytes != null && tileBytes.length != 0) {
                    tileImg[0][1] = BitmapFactory.decodeByteArray(tileBytes, 0,
                            tileBytes.length);
                }
                // Les 3 autres
                tileBytes = tileProvider.readRealTileImage(c - 1, r, zoom);
                if (tileBytes != null && tileBytes.length != 0) {
                    tileImg[0][0] = BitmapFactory.decodeByteArray(tileBytes, 0,
                            tileBytes.length);
                }
                tileBytes = tileProvider.readRealTileImage(c, r + 1, zoom);
                if (tileBytes != null && tileBytes.length != 0) {
                    tileImg[1][1] = BitmapFactory.decodeByteArray(tileBytes, 0,
                            tileBytes.length);
                }
                tileBytes = tileProvider.readRealTileImage(c - 1, r + 1, zoom);
                if (tileBytes != null && tileBytes.length != 0) {
                    tileImg[1][0] = BitmapFactory.decodeByteArray(tileBytes, 0,
                            tileBytes.length);
                }
            }
        } else { // Tuiles de gauche
            if (y <= 128) { // Tuile inférieure gauche
                mapOrigWmtsY -= 256;
                y += 256;
                tileBytes = tileProvider.readRealTileImage(c, r, zoom);
                if (tileBytes != null && tileBytes.length != 0) {
                    tileImg[1][0] = BitmapFactory.decodeByteArray(tileBytes, 0,
                            tileBytes.length);
                }
                // Les 3 autres
                tileBytes = tileProvider.readRealTileImage(c + 1, r, zoom);
                if (tileBytes != null && tileBytes.length != 0) {
                    tileImg[1][1] = BitmapFactory.decodeByteArray(tileBytes, 0,
                            tileBytes.length);
                }
                tileBytes = tileProvider.readRealTileImage(c, r - 1, zoom);
                if (tileBytes != null && tileBytes.length != 0) {
                    tileImg[0][0] = BitmapFactory.decodeByteArray(tileBytes, 0,
                            tileBytes.length);
                }
                tileBytes = tileProvider.readRealTileImage(c + 1, r - 1, zoom);
                if (tileBytes != null && tileBytes.length != 0) {
                    tileImg[0][1] = BitmapFactory.decodeByteArray(tileBytes, 0,
                            tileBytes.length);
                }
            } else { // Tuile supérieure gauche
                tileBytes = tileProvider.readRealTileImage(c, r, zoom);
                if (tileBytes != null && tileBytes.length != 0) {
                    tileImg[0][0] = BitmapFactory.decodeByteArray(tileBytes, 0,
                            tileBytes.length);
                }
                // Les 3 autres
                tileBytes = tileProvider.readRealTileImage(c + 1, r, zoom);
                if (tileBytes != null && tileBytes.length != 0) {
                    tileImg[0][1] = BitmapFactory.decodeByteArray(tileBytes, 0,
                            tileBytes.length);
                }
                tileBytes = tileProvider.readRealTileImage(c, r + 1, zoom);
                if (tileBytes != null && tileBytes.length != 0) {
                    tileImg[1][0] = BitmapFactory.decodeByteArray(tileBytes, 0,
                            tileBytes.length);
                }
                tileBytes = tileProvider.readRealTileImage(c + 1, r + 1, zoom);
                if (tileBytes != null && tileBytes.length != 0) {
                    tileImg[1][1] = BitmapFactory.decodeByteArray(tileBytes, 0,
                            tileBytes.length);
                }
            }
        }

        // Créer l'image modifiable 512x512
        Bitmap img = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(img);

        // Récupérer les 4 tuiles et créer l'image de notification
        if (tileImg[0][0] != null) {
            canvas.drawBitmap(tileImg[0][0], 0, 0, paint);
        }
        if (tileImg[0][1] != null) {
            canvas.drawBitmap(tileImg[0][1], 256, 0, paint);
        }
        if (tileImg[1][0] != null) {
            canvas.drawBitmap(tileImg[1][0], 0, 256, paint);
        }
        if (tileImg[1][1] != null) {
            canvas.drawBitmap(tileImg[1][1], 256, 256, paint);
        }

        if (draw) { // Dessiner la position courante
            // et la partie de trace visible dans les 4 tuiles
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x, y, 5, paint);

            GeoLocation loc;
            Path track = new Path();
            track.moveTo(x, y);
            boolean up = false;
            float xx, yy;
            for (int i = locList.size() - 2; i >= 0; i--) {
                loc = locList.get(i);
                xx = (float) (WMTS.longToWmtsX(loc.longitude)
                        / tileDim * 256 - mapOrigWmtsX);
                yy = (float) (WMTS.latToWmtsY(loc.latitude)
                        / tileDim * 256 - mapOrigWmtsY);
                if (xx >= 0 && xx < 512 && yy >= 0 && yy < 512) {
                    if (up) {
                        track.moveTo(xx, yy);
                        up = false;
                    } else {
                        track.lineTo(xx, yy);
                    }
                } else {
                    up = true;
                }
            }
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawPath(track, paint);
        }

        // Découper l'image de manière à placer la position au centre
        return Bitmap.createBitmap(img, (int) (x - 128), (int) (y - 128),
                256, 256);
    }

    /**
     * Tâche de sauvegarde périodique de l'enregistrement en cours.
     */
    private class SavingTask extends TimerTask {
        public void purgeCache() {
            File outputFile;
            String name = recordBundle.getString(KMLReader.PATHNAME_KEY);
            if (pressureSensor == null) {
                outputFile = new File(name);
            } else {
                int suffIdx = name.lastIndexOf('.');
                outputFile = new File(name.substring(0, suffIdx) + "-bar.kml");
            }
            outputFile.delete();
        }

        @Override
        public void run() {
            ArrayList<GeoLocation> locList = recordBundle
                    .getParcelableArrayList(KMLReader.LOCATIONS_KEY);
            if (locList == null || locList.isEmpty())
                return; // Aucune position valide

            File outputFile;
            String name = recordBundle.getString(KMLReader.PATHNAME_KEY);
            if (pressureSensor == null) { // données avec altitudes GPS
                outputFile = new File(name);
                try {
                    FileOutputStream outputStream = new FileOutputStream(outputFile);
                    if (outputStream != null)
                        KMLWriter.writeKMLFile(
                                recordBundle.getParcelableArrayList(KMLReader.LOCATIONS_KEY),
                                outputStream, true);
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }
            } else { // données avec altitude barométriques
                int suffIdx = name.lastIndexOf('.');
                outputFile = new File(name.substring(0, suffIdx) + "-bar.kml");
                try {
                    FileOutputStream outputStream = new FileOutputStream(outputFile);
                    if (outputStream != null)
                        KMLWriter.writeKMLFile(
                                recordBundle.getParcelableArrayList(KMLReader.LOCATIONS_KEY),
                                outputStream, false);
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Interface de connexion pour les clients
     */
    public class MyBinder extends Binder {
        /**
         * Accès aux méthodes publiques du Service.
         *
         * @return instance du Service
         */
        public GeoLocTrackService getService() {
            return GeoLocTrackService.this;
        }

        /**
         * Accès aux données de l'enregistrement.
         *
         * @return Bundle contenant les données
         */
        public Bundle getRecordData() {
            ArrayList<GeoLocation> locList = recordBundle
                    .getParcelableArrayList(KMLReader.LOCATIONS_KEY);
            if (locList != null && locList.size() > 0) {
                return recordBundle;
            } else {
                return null;
            }
        }

        /**
         * Vérifier si un enregistrement est en cours.
         *
         * @return vrai si un enregistrement est en cours.
         */
        public boolean isRecording() {
            return rstate == RecordingState.STARTED;
        }
    }

}