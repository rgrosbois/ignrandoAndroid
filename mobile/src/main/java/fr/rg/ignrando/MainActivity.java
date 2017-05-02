package fr.rg.ignrando;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.ActionBar;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnSystemUiVisibilityChangeListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.SearchView;
import android.widget.TextView;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.GoogleMap.InfoWindowAdapter;
import com.google.android.gms.maps.GoogleMap.OnMapLongClickListener;
import com.google.android.gms.maps.GoogleMapOptions;
import com.google.android.gms.maps.MapFragment;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import fr.rg.ignrando.dialog.DeleteFileDialog;
import fr.rg.ignrando.dialog.RecordDialog;
import fr.rg.ignrando.dialog.RenameFileDialog;
import fr.rg.ignrando.util.GeoLocation;
import fr.rg.ignrando.util.KMLReader;
import fr.rg.ignrando.util.Track;

public class MainActivity extends Activity implements
        FileListFragment.Callbacks, RenameFileDialog.Callbacks,
        DeleteFileDialog.Callbacks, OnMapLongClickListener,
        TrackInfoFragment.SubTrackSelectionListener, OnMapReadyCallback {
    // Étiquette de débogage
    public static final String DEBUG_TAG = "GPS_LOGGER";

    // Étiquettes de fragments
    private static final String FRAG_GMAP_TAG = "GMAP_TAG";
    private static final String FRAG_FILELIST_TAG = "FILELIST_TAG";
    private final static String FRAG_PATHINFO_TAG = "PATHINFO_TAG";
    private final static String FRAG_PREFS_TAG = "SETTINGS_TAG";

    // Clés du bundle de récupération
    private static final String KML_BUNDLE_KEY = "kml_bundle_key";
    private static final String LATITUDE_PREF_KEY = "saved_latitude";
    private static final String LONGITUDE_PREF_KEY = "saved_longitude";
    private static final String BEARING_PREF_KEY = "saved_bearing";
    private static final String TILT_PREF_KEY = "saved_tilt";
    private static final String ZOOM_PREF_KEY = "saved_zoom";
    private static final String MAPTYPE_PREF_KEY = "saved_maptype";
    private static final String FULLSCREEN_PREF_KEY = "saved_fullscreen";
    private static final String SUBTITLE_KEY = "saved_subtitle";

    // Gestion des permissions
    public boolean canAccessFineLocation = false;
    public boolean canReadExtMem = false;
    public static final int MY_PERMISSION_READ_EXTERNAL_STORAGE = 1;
    public static final int MY_PERMISSION_ACCESS_FINE_LOCATION = 2;

    // Menu de recherche
    private MenuItem searchItem;

    // Carte GoogleMap
    private GoogleMap map = null;
    private TileOverlay ignOverlay; // surcouche IGN

    private boolean configurationHasChanged = false;

    // Traces KML
    private Track kmlTrack = new Track();
    private Track recordTrack = new Track();
    private boolean trackOnMapisKML = false;
    private boolean isFirstGeoLocation = false;
    private Bitmap kmlTrackStartImg, kmlTrackEndImg, recTrackStartImg,
            recTrackEndImg, interImg; // Images d'extrémités
    private int trackWidth = 3; // largeur de trace (en pixel indépendant)

    // Association au service de tracking de géolocalisations
    private GeoLocTrackService.MyBinder locBinder = null;
    private boolean isConnectedToGeoLocService = false;
    private ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            locBinder = (GeoLocTrackService.MyBinder) binder;
            isConnectedToGeoLocService = true;

            if (locBinder.isRecording()) { // Mettre à jour la trace d'enregistrement
                recordTrack.b = locBinder.getRecordData();
                addPathToFragments(false, false);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isConnectedToGeoLocService = false;
        }

    };

    /**
     * Écouter l'arrivée d'une nouvelle géolocalisation.
     */
    private BroadcastReceiver mUpdateReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(GeoLocTrackService.PATH_UPDATED)) {
                // Renvoyer toutes les données de la trace (à améliorer)
                if (isConnectedToGeoLocService) {
                    Bundle b = locBinder.getRecordData();
                    if (b != null) {
                        recordTrack.b = b;
                        // Mettre à jour la carte
                        addPathToFragments(false, isFirstGeoLocation);
                        isFirstGeoLocation = false;
                        updateSubtitle();
                    }
                }
            }
        }
    };

    /**
     * Gérer le résultat de demande d'autorisations auprès de l'utilisateur.
     *
     * @param requestCode  code arbitraire associée à la demande
     * @param permissions  tableau de permissions demandées
     * @param grantResults résultat pour chaque permission
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSION_ACCESS_FINE_LOCATION:
                canAccessFineLocation = (grantResults.length >= 0 &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED);
                break;
            case MY_PERMISSION_READ_EXTERNAL_STORAGE:
                canReadExtMem = (grantResults.length >= 0 &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED);
                break;
        }
    }

    /**
     * Création de l'activité
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Utilisation d'une barre de progression
        requestWindowFeature(Window.FEATURE_PROGRESS);

        // Contenu de l'interface graphique
        setContentView(R.layout.activity_main);

        // Définir le titre de l'application
        setTitle(getString(R.string.app_longname));

        // Vérifier la permission d'accès aux positions GPS
        if (ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED) {
            canAccessFineLocation = true;
        } else {
            canAccessFineLocation = false;
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    MY_PERMISSION_ACCESS_FINE_LOCATION);
        }

        // Placer le fragment de carte
        MapFragment mf = (MapFragment) getFragmentManager().findFragmentByTag(FRAG_GMAP_TAG);
        if (mf == null) { // Créer le fragment
            mf = getMapFragment();
            getFragmentManager().beginTransaction()
                    .add(R.id.container, mf, FRAG_GMAP_TAG).commit();
        } else { // Le fragment existe déjà
            getFragmentManager().beginTransaction().attach(mf).commit();
        }

        // Charger les images de marqueurs
        kmlTrackStartImg = BitmapFactory.decodeResource(getResources(),
                R.drawable.depart_kml);
        kmlTrackEndImg = BitmapFactory.decodeResource(getResources(),
                R.drawable.arrivee_kml);
        recTrackStartImg = BitmapFactory.decodeResource(getResources(),
                R.drawable.depart_enregistrement);
        recTrackEndImg = BitmapFactory.decodeResource(getResources(),
                R.drawable.arrivee_enregistrement);
        interImg = BitmapFactory.decodeResource(getResources(),
                R.drawable.intermediaire);

        // Largeur de trace: transformation de pixel indépendant vers pixel réel
        DisplayMetrics metrics = new DisplayMetrics();
        ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay().getMetrics(metrics);
        trackWidth *= metrics.density;

        // Utilisation du bouton Home (pour sélectionner un fichier KML)
        ActionBar actionBar = getActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);

        // Supprimer l'action bar en mode plein écran
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(
                new OnSystemUiVisibilityChangeListener() {

                    @Override
                    public void onSystemUiVisibilityChange(int visibility) {
                        if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                            getActionBar().show();
                            getWindow().getDecorView().setSystemUiVisibility(0);
                        } else { // Mode plein écran
                            getActionBar().hide();
                        }
                    }
                });

        // Retrouver le mode immersion et l'éventuelle trace KML
        // après un changement de configuration
        if (savedInstanceState != null) {
            configurationHasChanged = true;
            kmlTrack.b = savedInstanceState.getParcelable(KML_BUNDLE_KEY);
            if (savedInstanceState.getBoolean(FULLSCREEN_PREF_KEY, false)) {
                hideUiDecoration();
            }
            getActionBar().setSubtitle(savedInstanceState.getString(SUBTITLE_KEY));
        }

        // Démarrer le service de Géolocalisation (action nulle si déjà démarré)
        startService(new Intent(this, GeoLocTrackService.class));
    }

    /**
     * Se connecter au service d'enregistrement des géolocalisations.
     */
    @Override
    protected void onStart() {
        super.onStart();

        bindService(new Intent(this, GeoLocTrackService.class), mConnection,
                Context.BIND_AUTO_CREATE);
    }

    /**
     * L'activité (re)passe au premier plan
     */
    @Override
    protected void onResume() {
        super.onResume();
        // Mettre à jour la carte
        setupMapOnResume();

        // Surveiller les nouvelles géolocalisations
        registerReceiver(mUpdateReceiver, new IntentFilter(
                GeoLocTrackService.PATH_UPDATED));
        isFirstGeoLocation = true;
    }

    /**
     * Sauvegarder l'état courant avant un changement de configuration.
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        // Données de la trace KML
        if (kmlTrack.b != null) {
            outState.putParcelable(KML_BUNDLE_KEY, kmlTrack.b);
        }

        // Position de la caméra
        if (map != null) {
            CameraPosition pos = map.getCameraPosition();
            SharedPreferences settings = getPreferences(MODE_PRIVATE);
            SharedPreferences.Editor editor = settings.edit();
            editor.putFloat(LATITUDE_PREF_KEY, (float) pos.target.latitude);
            editor.putFloat(LONGITUDE_PREF_KEY, (float) pos.target.longitude);
            editor.putFloat(BEARING_PREF_KEY, pos.bearing);
            editor.putFloat(TILT_PREF_KEY, pos.tilt);
            editor.putFloat(ZOOM_PREF_KEY, pos.zoom);
            editor.putInt(MAPTYPE_PREF_KEY, map.getMapType());
            editor.apply(); // Appel asynchrone
        }

        // Mode immersion
        boolean isFullScreen = (getWindow().getDecorView().getSystemUiVisibility() & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0;
        outState.putBoolean(FULLSCREEN_PREF_KEY, isFullScreen);

        // Sous-titre de la barre d'action
        if (getActionBar().getSubtitle() != null) {
            outState.putString(SUBTITLE_KEY, getActionBar().getSubtitle().toString());
        }
    }

    /**
     * Arrêter les écoutes et services lorsque l'activité passe en second plan.
     */
    @Override
    protected void onPause() {
        super.onPause();
        // surveillance des géolocalisations
        unregisterReceiver(mUpdateReceiver);
    }

    /**
     * Se déconnecter du service d'enregistrement des géolocalisations
     */
    @Override
    protected void onStop() {
        unbindService(mConnection);

        super.onStop();
    }

    /**
     * Menus/actions de l'application.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Charger le contenu
        getMenuInflater().inflate(R.menu.main_menu, menu);

        // Sélectionner le type de carte en cours de visualisation
        SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(this);
        int val = Integer.parseInt(prefs.getString("pref_maptype_choices", "0"));
        switch (val) {
            case 0: // IGN
                ((MenuItem) menu.findItem(R.id.ign_maptype)).setChecked(true);
                break;
            case 2: // Hybrid
                ((MenuItem) menu.findItem(R.id.hybrid_maptype)).setChecked(true);
                break;
            case 4: // Field
                ((MenuItem) menu.findItem(R.id.field_maptype)).setChecked(true);
                break;
            case 5: // None
                ((MenuItem) menu.findItem(R.id.none_maptype)).setChecked(true);
                break;
        }

        // Achever l'initialisation de l'action de recherche
        SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
        searchItem = menu.findItem(R.id.search);
        SearchView searchView = (SearchView) searchItem.getActionView();
        searchView.setSearchableInfo(searchManager
                .getSearchableInfo(getComponentName()));

        return true;
    }

    /**
     * Gestion du clic sur une action/menu
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        FragmentTransaction ft;

        switch (item.getItemId()) {
            case android.R.id.home: // Bouton Home -> Liste de fichiers
                if (getFragmentManager().findFragmentByTag(FRAG_FILELIST_TAG) != null) {
                    getFragmentManager().popBackStack();
                } else {
                    ft = getFragmentManager().beginTransaction();
                    FileListFragment f = getFileListFragment();
                    f.majListeFichiers();
                    ft.replace(R.id.container, f, FRAG_FILELIST_TAG);
                    ft.addToBackStack(null);
                    ft.commit();
                }
                break;
            case R.id.ign_maptype: // Afficher la carte IGN
                handleMapTypeSelection(item, 0);
                break;
            case R.id.hybrid_maptype: // Afficher la carte GoogleMap hybride
                handleMapTypeSelection(item, 2);
                break;
            case R.id.field_maptype: // Afficher la carte GoogleMap relief
                handleMapTypeSelection(item, 4);
                break;
            case R.id.none_maptype: // N'afficher aucune carte
                handleMapTypeSelection(item, 5);
                break;
            case R.id.fullscreen: // Mode plein écran
                hideUiDecoration();
                break;
            case R.id.info: // Afficher les informations de la trace
                if (getFragmentManager().findFragmentByTag(FRAG_PATHINFO_TAG) != null) {
                    // Fragment info déjà affiché -> revenir à la carte
                    getFragmentManager().popBackStack();
                } else { // Créer et afficher le fragment d'information
                    ft = getFragmentManager().beginTransaction();
                    TrackInfoFragment pif;
                    if (isConnectedToGeoLocService && locBinder.isRecording()) {
                        // Enregistrement en cours, récupérer les données
                        recordTrack.b = locBinder.getRecordData();
                        pif = TrackInfoFragment.newInstance(recordTrack.b);
                        pif.setSelectionIdx(recordTrack.iSubTrackStart,
                                recordTrack.iSubTrackEnd);
                    } else if (kmlTrack.b != null) { // Trace KML préalablement chargée
                        pif = TrackInfoFragment.newInstance(kmlTrack.b);
                        pif.setSelectionIdx(kmlTrack.iSubTrackStart, kmlTrack.iSubTrackEnd);
                    } else { // Affichage vide
                        pif = new TrackInfoFragment();
                    }
                    // Activer l'écoute de sélection d'une sous-trace
                    pif.addSubTrackSelectionListener(this);

                    // Mettre en place le fragment
                    ft.replace(R.id.container, pif, FRAG_PATHINFO_TAG);
                    ft.addToBackStack(null);
                    ft.commit();
                }
                break;
            case R.id.deleteKMLPath: // Retirer la trace KML
                kmlTrack.b = null;
                addPathToFragments(true, false);
                updateSubtitle();
                return false;
            case R.id.preferences: // Fragment de préférences
                if (getFragmentManager().findFragmentByTag(FRAG_PREFS_TAG) != null) {
                    // Fragment déjà afficher -> revenir à la carte
                    getFragmentManager().popBackStack();
                } else { // Afficher le fragment
                    ft = getFragmentManager().beginTransaction();
                    ft.replace(R.id.container, getSettingsFragment(), FRAG_PREFS_TAG);
                    ft.addToBackStack(null);
                    ft.commit();
                }
                break;
            default: // Option non reconnue
                return super.onOptionsItemSelected(item);
        }
        return true;
    }

    /**
     * La requête arrive dans une intention.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            // Recherche d'une adresse
            String query = intent.getStringExtra(SearchManager.QUERY);
            onAddressTyped(query);
        }
    }

    /**
     * Récupérer les 5 géolocalisations correspondant à une adresse.
     *
     * @param address
     */
    public void onAddressTyped(final String address) {
        // Récupérer la clé IGN
        SharedPreferences settings = PreferenceManager
                .getDefaultSharedPreferences(this);
        final String cleIGNWeb = settings.getString("IGN_DEVELOPMENT_KEY", "");

        if (address != null) { // Adresse confirmée
            final int maxReponses = 5;
            new AsyncTask<Void, Void, GeoLocation[]>() {

                @Override
                protected GeoLocation[] doInBackground(Void... params) {
                    return IGNTileProvider.geolocate(address, maxReponses, cleIGNWeb);
                }

                @Override
                protected void onPostExecute(GeoLocation[] g) {
                    int count = 0;
                    for (int i = 0; i < g.length; i++) {
                        if (g[i] != null)
                            count++;
                    }
                    if (count == 0)
                        return;
                    else if (count == 1) {
                        onAddressSelected(g[0].address);
                    } else {
                        // Placer le fragment de suggestion
                        String[] liste = new String[count];
                        for (int i = 0; i < count; i++) {
                            liste[i] = g[i].address;
                        }
                        FragmentTransaction ft = getFragmentManager().beginTransaction();
                        ft.replace(R.id.container, SuggestFragment.newInstance(liste));
                        ft.addToBackStack(null);
                        ft.commit();
                    }
                }
            }.execute();
        }
    }

    /**
     * Récupérer la géolocalisation d'une adresse pour recentrer les cartes.
     *
     * @param address
     */
    public void onAddressSelected(final String address) {
        // Récupérer la clé IGN
        SharedPreferences settings = PreferenceManager
                .getDefaultSharedPreferences(this);
        final String cleIGNWeb = settings.getString("IGN_DEVELOPMENT_KEY", "");

        getFragmentManager().popBackStack(); // Enlever la liste de suggestion
        if (address != null) { // Adresse confirmée
            final int maxReponses = 1;
            new AsyncTask<Void, Void, GeoLocation[]>() {

                @Override
                protected GeoLocation[] doInBackground(Void... params) {
                    return IGNTileProvider.geolocate(address, maxReponses, cleIGNWeb);
                }

                @Override
                protected void onPostExecute(GeoLocation[] g) {
                    if (g != null && g[0] != null) {
                        if (g[0].address != null)
                            getActionBar().setSubtitle(g[0].address);

                        // Centrer la carte sur la géolocalisation
                        moveCamera2Loc(g[0].latitude, g[0].longitude);

                        // Remettre en icône l'action de la barre de recherche
                        if (searchItem != null)
                            searchItem.collapseActionView();
                    }
                }
            }.execute();
        }
    }

    /**
     * Extraire la trace du fichier KML et mettre à jour l'affichage. Cette tâche
     * pouvant être longue, elle est réalisée dans une AsynkTask.
     *
     * @param fileName Chemin vers le fichier
     * @see AsyncTask
     */
    @Override
    public void onKMLFileSelected(final String fileName) {
        // indicateur de progression
        setProgressBarVisibility(true);

        new AsyncTask<Void, Void, Bundle>() {
            @Override
            protected Bundle doInBackground(Void... voids) {
                return KMLReader.extractLocWithStAXCursor(fileName);
            }

            @Override
            protected void onPostExecute(Bundle bundle) {
                // Replacer le fragment de carte
                getFragmentManager().popBackStack();

                // Mettre à jour la carte
                kmlTrack.b = bundle;
                addPathToFragments(true, true);

                // Afficher le titre (si aucun enregistrement)
                updateSubtitle();

                // Enlever l'indicateur de progression
                setProgressBarVisibility(false);
            }
        }.execute();
    }

    /**
     * Méthode de rappel utilisée lorsque l'utilisateur supprime un fichier KML de
     * la liste.
     */
    @Override
    public void deleteKMLFile(String nom) {
        File file = new File(nom);
        file.delete();

        // Mettre à jour la liste des fichiers
        Fragment f;
        if ((f = getFragmentManager().findFragmentByTag(FRAG_FILELIST_TAG)) != null) {
            ((FileListFragment) f).majListeFichiers();
        }

        // Supprimer la trace s'il s'agit de celle qui est visualisée
        if (kmlTrack.b != null) {
            String kmlFileName = kmlTrack.b.getString(KMLReader.PATHNAME_KEY);
            if (kmlFileName.equals(nom)) {
                kmlTrack.b = null;
                addPathToFragments(true, false);
                updateSubtitle();
            }
        }
    }

    /**
     * Méthode de rappel appelée lorsque l'utilisateur renomme un fichier de la
     * liste.
     */
    @Override
    public void renameKMLFile(String ancienNom, String nouveauNom) {
        File file = new File(ancienNom);
        file.renameTo(new File(nouveauNom));

        // Mettre à jour la liste des fichiers
        Fragment f;
        if ((f = getFragmentManager().findFragmentByTag(FRAG_FILELIST_TAG)) != null) {
            ((FileListFragment) f).majListeFichiers();
        }

        // Mettre à jour le titre de trace s'il s'agit de celle qui est visualisée
        if (kmlTrack.b != null) {
            String kmlFileName = kmlTrack.b.getString(KMLReader.PATHNAME_KEY);
            if (kmlFileName.equals(ancienNom)) {
                kmlTrack.b.putString(KMLReader.PATHNAME_KEY, nouveauNom);
                updateSubtitle();
            }
        }
    }

    /**
     * Récupérer la référence vers le fragment de carte. Le créer si nécessaire.
     *
     * @return
     */
    private MapFragment getMapFragment() {
        MapFragment f = (MapFragment) getFragmentManager().findFragmentByTag(FRAG_GMAP_TAG);
        if (f == null) {
            // Dernière position de la caméra
            SharedPreferences settings = getPreferences(MODE_PRIVATE);
            float latitude = settings.getFloat(LATITUDE_PREF_KEY, 45.145f);
            float longitude = settings.getFloat(LONGITUDE_PREF_KEY, 5.72f);
            float bearing = settings.getFloat(BEARING_PREF_KEY, 0);
            float tilt = settings.getFloat(TILT_PREF_KEY, 0);
            float zoom = settings.getFloat(ZOOM_PREF_KEY, 15);

            // Options de la carte
            GoogleMapOptions options = new GoogleMapOptions()
                    .mapType(GoogleMap.MAP_TYPE_NONE)
                    .camera(new CameraPosition(new LatLng(latitude, longitude), // centre
                            zoom, // zoom
                            tilt, // tilt
                            bearing)) // bearing
                    .zoomControlsEnabled(false).compassEnabled(true);
            f = MapFragment.newInstance(options);
        }
        // Surveillance asynchrone de la création de la carte
        f.getMapAsync(this);

        return (MapFragment) f;
    }

    /**
     * Récupérer la référence vers le fragment de liste de fichiers. Le créer si
     * nécessaire.
     *
     * @return
     */
    private FileListFragment getFileListFragment() {
        Fragment f;
        if ((f = getFragmentManager().findFragmentByTag(FRAG_FILELIST_TAG)) == null) {
            f = new FileListFragment();
        }
        return (FileListFragment) f;
    }

    /**
     * Créer le fragment si nécessaire
     *
     * @return
     */
    private Fragment getSettingsFragment() {
        Fragment f;
        if ((f = getFragmentManager().findFragmentByTag(FRAG_PREFS_TAG)) == null) {
            f = new SettingsFragment();
        }
        return f;
    }

    /**
     * Ajouter/enlever une trace sur la carte
     *
     * @param mMap carte où ajouter la trace.
     */
    private void drawTrackOnMap(Track t, int color, GoogleMap mMap,
                                Bitmap depImg, Bitmap arrImg) {

        // Effacer l'éventuelle sous-trace
        if (t.plSubTrack != null) {
            t.plSubTrack.remove();
        }
        // Effacer la trace et ses marqueurs
        if (t.plTrack != null) {
            t.plTrack.remove();
        }
        if (t.markStart != null) {
            t.markStart.remove();
        }
        if (t.markEnd != null) {
            t.markEnd.remove();
        }
        if (t.markInter != null) {
            t.markInter.remove();
        }

        // Pas de donnée -> Supprimer la trace
        if (t.b == null) {
            t.plTrack = null;
            t.markStart = null;
            t.markEnd = null;
            return;
        }

        // Créer la trace (et la sous-trace)
        ArrayList<GeoLocation> ga = t.b
                .getParcelableArrayList(KMLReader.LOCATIONS_KEY);
        if (ga == null || ga.size() == 0) { // La trace a une longueur nulle
            return;
        }

        // ======== Trace ===========
        PolylineOptions track = new PolylineOptions(); // Trace ou sous-trace
        GeoLocation g;
        for (int i = 0; i <= ga.size() - 1; i++) {
            g = (GeoLocation) ga.get(i);
            track.add(new LatLng(g.latitude, g.longitude));
        }
        // Marqueur de départ
        g = (GeoLocation) ga.get(0);
        // Log.d(DEBUG_TAG, "depImg=" + depImg + "(" + kmlTrackStartImg + "),arrImg="
        // + arrImg);
        t.markStart = mMap.addMarker(new MarkerOptions()
                .position(new LatLng(g.latitude, g.longitude))
                .title("Départ")
                .snippet(
                        String.format("%d/%d/%d/%.6f/%.6f", g.timeStampS, g.length,
                                (int) (g.barElevation == 0 ? g.gpsElevation : g.barElevation),
                                g.latitude, g.longitude))
                .icon(BitmapDescriptorFactory.fromBitmap(depImg)));
        // Marqueur d'arrivée
        if (ga.size() > 1) {
            g = (GeoLocation) ga.get(ga.size() - 1);
            t.markEnd = mMap.addMarker(new MarkerOptions()
                    .position(new LatLng(g.latitude, g.longitude))
                    .title("Arrivée")
                    .snippet(
                            String
                                    .format("%d/%d/%d/%.6f/%.6f", g.timeStampS, g.length,
                                            (int) (g.barElevation == 0 ? g.gpsElevation
                                                    : g.barElevation), g.latitude, g.longitude))
                    .icon(BitmapDescriptorFactory.fromBitmap(arrImg)));
            // Sauvegarder la trace
            track.color(color).width(trackWidth).zIndex(1f);
            t.plTrack = mMap.addPolyline(track);
        }

        // == Sous-Trace éventuelle ==
        // Log.d(DEBUG_TAG, "sous trace="+t.iSubTrackStart+" à "+t.iSubTrackEnd);
        if (t.iSubTrackStart == -1 || t.iSubTrackEnd == -1)
            return; // pas de sélection
        if (t.iSubTrackEnd == t.iSubTrackStart) { // Une seule position
            g = (GeoLocation) ga.get(t.iSubTrackStart);
            t.markInter = mMap.addMarker(new MarkerOptions()
                    .position(new LatLng(g.latitude, g.longitude))
                    .title("Position intermédiaire")
                    .snippet(
                            String
                                    .format("%d/%d/%d/%.6f/%.6f", g.timeStampS, g.length,
                                            (int) (g.barElevation == 0 ? g.gpsElevation
                                                    : g.barElevation), g.latitude, g.longitude))
                    .icon(BitmapDescriptorFactory.fromBitmap(interImg)));
            return;
        }
        track = new PolylineOptions(); // sous-trace

        // Positions successives
        for (int i = t.iSubTrackStart; i <= t.iSubTrackEnd; i++) {
            g = (GeoLocation) ga.get(i);
            track.add(new LatLng(g.latitude, g.longitude));
        }
        // Sauvegarder la sous-trace
        track.color(Color.argb(128, 255, 0, 0)).width(3 * trackWidth).zIndex(1f);
        t.plSubTrack = mMap.addPolyline(track);
    }

    /**
     * Ajouter une trace (de type KML ou d'enregistrement) sur les fragments
     * d'information et de carte en ajoutant ou non une animation de la caméra.
     *
     * @param isKML         La trace est de type KML
     * @param animateCamera Animer la caméra sur la carte
     */
    private void addPathToFragments(boolean isKML, boolean animateCamera) {
        // Récupérer les données de la bonne trace
        Bundle b;
        if (isKML) {
            b = kmlTrack.b;
        } else {
            b = recordTrack.b;
        }

        // Ajouter sur le fragment d'information
        TrackInfoFragment pif = (TrackInfoFragment) getFragmentManager()
                .findFragmentByTag(FRAG_PATHINFO_TAG);
        if (pif != null) { // fragment disponible
            pif.addPath(b);
        }

        // Ajouter sur le fragment de carte
        MapFragment gmf = (MapFragment) getFragmentManager().findFragmentByTag(
                FRAG_GMAP_TAG);
        if (gmf == null) { // Pas de fragment
            return;
        }
        if (map == null) { // La carte n'est pas encore créée
            return;
        }
        if (isKML) { // Trace KML
            drawTrackOnMap(kmlTrack, getResources().getColor(R.color.kml_path), map,
                    kmlTrackStartImg, kmlTrackEndImg);
            trackOnMapisKML = true;
        } else { // Trace d'enregistrement
            drawTrackOnMap(recordTrack, getResources().getColor(R.color.record_path),
                    map, recTrackStartImg, recTrackEndImg);
            trackOnMapisKML = false;
        }

        if (b != null && animateCamera) { // Animation de la caméra
            double latMin = b.getDouble(KMLReader.LATMIN_KEY);
            double latMax = b.getDouble(KMLReader.LATMAX_KEY);
            double longMin = b.getDouble(KMLReader.LONGMIN_KEY);
            double longMax = b.getDouble(KMLReader.LONGMAX_KEY);
            if (latMin == latMax && longMin == longMax) {
                // La trace ne contient qu'une position ou les positions
                // sont alignées (verticalement ou horizontalement)
                moveCamera2Loc(latMin, longMin);
            } else {
                moveCamera2Area(latMin, latMax, longMin, longMax);
            }
        }
    }

    /**
     * Déplacer la caméra de manière à afficher une zone spécifique sur la carte.
     *
     * @param latMin  Latitude minimale
     * @param latMax  Latitude maximale
     * @param longMin Longitude minimale
     * @param longMax Longitude maximale
     */
    private void moveCamera2Area(double latMin, double latMax, double longMin,
                                 double longMax) {
        MapFragment gmf = (MapFragment) getFragmentManager().findFragmentByTag(
                FRAG_GMAP_TAG);
        if (gmf == null) { // Le fragment n'existe pas
            return;
        }

        if (map == null) {
            return; // La carte n'est pas encore créée
        }

        double deltaLat = latMax - latMin;
        double deltaLong = longMax - longMin;
        if (deltaLat * deltaLong == 0) {
            float zoom = Math.max(map.getCameraPosition().zoom, 17);
            // 1 seule position ou
            // toutes les positions sont sur une même latitude ou longitude:
            // placer la position au centre tout en conservant le niveau de zoom
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(latMin,
                    longMin), zoom));
        } else { // déplacer la caméra et modifier le niveau de zoom pour afficher
            // l'intégralité de la trace
            DisplayMetrics metrics = new DisplayMetrics();
            ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay().getMetrics(metrics);
            int width = metrics.widthPixels;
            int height = metrics.heightPixels;

            map.animateCamera(CameraUpdateFactory.newLatLngBounds(new LatLngBounds(
                            new LatLng(latMin, longMin), new LatLng(latMax, longMax)), width,
                    height * 3 / 4, 50));
        }

    }

    /**
     * Déplacer la caméra de manière à placer la géolocalisation spécifiée au
     * centre de la carte.
     *
     * @param latitude  Latitude de la géolocalisation
     * @param longitude Longitude de la géolocalisation
     */
    private void moveCamera2Loc(double latitude, double longitude) {
        MapFragment gmf = (MapFragment) getFragmentManager().findFragmentByTag(
                FRAG_GMAP_TAG);
        if (gmf == null) { // Le fragment n'existe pas
            return;
        }
        if (map == null) {
            return; // La carte n'est pas encore créée
        }

        float zoom = Math.max(map.getCameraPosition().zoom, 17);
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(latitude,
                longitude), zoom));

    }

    /**
     * Gérer le clic sur un nouveau type de carte.
     *
     * @param item
     * @param mapTypeNum
     */
    private void handleMapTypeSelection(MenuItem item, int mapTypeNum) {
        if (!item.isChecked()) {
            setupMapType(mapTypeNum);
            item.setChecked(true);
            // Sauvegarder la préférence
            SharedPreferences.Editor editor = PreferenceManager
                    .getDefaultSharedPreferences(this).edit();
            editor.putString("pref_maptype_choices", "" + mapTypeNum);
            editor.apply(); // Appel asynchrone
        }
    }

    /**
     * Afficher le type de carte désiré
     *
     * @param val
     */
    private void setupMapType(int val) {
        MapFragment gmf = (MapFragment) getFragmentManager().findFragmentByTag(
                FRAG_GMAP_TAG);
        if (gmf != null) {
            if (map == null) {
                return;
            }
            switch (val) {
                case 0: // IGN
                    map.setMapType(GoogleMap.MAP_TYPE_NONE);
                    addIGNOverlay();
                    break;
                case 1: // Normale
                    removeIGNOverlay();
                    map.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                    break;
                case 2: // Hybride
                    removeIGNOverlay();
                    map.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                    break;
                case 3: // Satellite
                    removeIGNOverlay();
                    map.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
                    break;
                case 4: // Terrain
                    removeIGNOverlay();
                    map.setMapType(GoogleMap.MAP_TYPE_TERRAIN);
                    break;
                case 5: // Aucune
                    removeIGNOverlay();
                    map.setMapType(GoogleMap.MAP_TYPE_NONE);
                    break;
            }

            // Enregistrer le nouveau type de carte
            SharedPreferences prefs = PreferenceManager
                    .getDefaultSharedPreferences(MainActivity.this);
            if (val != prefs.getInt(MAPTYPE_PREF_KEY, 0)) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putInt(MAPTYPE_PREF_KEY, val);
                editor.apply();
            }
        }
    }

    /**
     * Ajouter la surcouche carte IGN.
     */
    private void addIGNOverlay() {
        MapFragment mapFragment = (MapFragment) getFragmentManager()
                .findFragmentByTag(FRAG_GMAP_TAG);
        if (mapFragment != null) {
            if (map != null) {
                if (ignOverlay != null) {
                    ignOverlay.remove();
                }
                ignOverlay = map.addTileOverlay(new TileOverlayOptions()
                        .tileProvider(new IGNTileProvider(getApplicationContext()))
                        .fadeIn(true).zIndex(0.5f));
            }
        }
    }

    /**
     * Enlever la surcouche IGN
     */
    private void removeIGNOverlay() {
        if (ignOverlay != null) {
            ignOverlay.remove();
        }
    }

    /**
     * Passer en mode plein écran. Les méthodes utilisées dépendent de la version
     * d'Android.
     */
    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void hideUiDecoration() {
        if (Build.VERSION.SDK_INT < 16)
            return;

        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN // Barre d'état
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION // Barre de navigation
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (Build.VERSION.SDK_INT >= 19) {
            uiOptions |= View.SYSTEM_UI_FLAG_IMMERSIVE;
        }
        decorView.setSystemUiVisibility(uiOptions);
    }

    /**
     * Gestion du clic long sur la carte
     */
    @Override
    public void onMapLongClick(LatLng point) {
        if (isConnectedToGeoLocService) {
            if (locBinder.isRecording()) { // Déjà en cours
                RecordDialog.newInstance(true).show(getFragmentManager(),
                        "Enregistrement");
            } else { // Nouvel enregistrement
                if (canAccessFineLocation) {
                    locBinder.getService().startGeoLocationTracking();
                    RecordDialog.newInstance(false).show(getFragmentManager(),
                            "Enregistrement");
                }
            }
        }
    }

    /**
     * Démarrer un enregistrement GPS.
     */
    public void startRecord() {
        if (isConnectedToGeoLocService && canAccessFineLocation) {
            GeoLocTrackService locService = locBinder.getService();
            if (locService != null) {
                locService.startRecording();
                updateSubtitle();
            }
        }
    }

    /**
     * Annuler l'enregistrement.
     */
    public void cancelRecord() {
        if (isConnectedToGeoLocService) {
            GeoLocTrackService locService = locBinder.getService();
            if (locService != null) {
                locService.cancelRecording();
                locService.stopGeoLocationTracking();
                recordTrack.b = null;
                addPathToFragments(false, false); // Effacer la trace
                updateSubtitle();
            }
        }
    }

    /**
     * Stopper un enregistrement GPS
     */
    public void stopRecord() {
        if (isConnectedToGeoLocService) {
            GeoLocTrackService locService = locBinder.getService();
            if (locService != null) {
                locService.stopRecording();
                locService.stopGeoLocationTracking();
                recordTrack.b = null;
                addPathToFragments(false, false); // Effacer la trace
                updateSubtitle();
            }
        }
    }

    /**
     * Indiquer la tâche courante comme sous-titre de l'application
     */
    private void updateSubtitle() {
        if (isConnectedToGeoLocService && locBinder.isRecording()) {
            // Un enregistrement est en cours
            Bundle b = locBinder.getRecordData();
            if (b != null) {
                ArrayList<GeoLocation> locList = b
                        .getParcelableArrayList(KMLReader.LOCATIONS_KEY);
                getActionBar().setSubtitle(
                        getString(R.string.record_in_progress) + locList.size());
            } else {
                getActionBar()
                        .setSubtitle(getString(R.string.record_in_progress) + "0");
            }
        } else if (kmlTrack.b != null) { // Visualisation d'un fichier KML
            ArrayList<GeoLocation> locList = kmlTrack.b
                    .getParcelableArrayList(KMLReader.LOCATIONS_KEY);
            getActionBar().setSubtitle(
                    new File(kmlTrack.b.getString(KMLReader.PATHNAME_KEY)).getName()
                            + " (" + locList.size() + ")");
        } else {
            getActionBar().setSubtitle(null);
        }
    }

    /**
     * Terminer la configuration de la carte lorsque l'activité repasse au premier
     * plan:
     * <ul>
     * <li>Type de carte</li>
     * <li>Format des bulles d'information</li>
     * <li>Clic long</li>
     * <li>Affichage de la position courante</li>
     * <li>Mise à jour de l'affichage des traces.</li>
     * </ul>
     */
    private void setupMapOnResume() {
        // Récupérer la référence du fragment de carte
        MapFragment mapFragment = (MapFragment) getFragmentManager()
                .findFragmentByTag(FRAG_GMAP_TAG);
        if (mapFragment == null) {
            return;
        }

        // Récupérer la référence de la carte
        if (map == null) {
            return;
        }

        // Modifier si nécessaire le type de carte
        SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(MainActivity.this);
        setupMapType(prefs.getInt(MAPTYPE_PREF_KEY, 0));

        // Format des bulles d'information de position spécifiques
        // sur une trace.
        map.setInfoWindowAdapter(new InfoWindowAdapter() {

            @Override
            public View getInfoWindow(Marker marker) {
                return null;
            }

            @Override
            public View getInfoContents(Marker marker) {
                View v = getLayoutInflater().inflate(R.layout.infowindow, null);

                TextView tvTitle = (TextView) v.findViewById(R.id.iw_title);
                tvTitle.setText(marker.getTitle());

                String info[] = marker.getSnippet().split("/");

                // Date
                TextView tvDate = (TextView) v.findViewById(R.id.iw_date);
                Date date = new Date(Long.parseLong(info[0]) * 1000);
                DateFormat df = new SimpleDateFormat("EEE dd MMM yyyy à HH:mm:ss",
                        Locale.FRANCE);
                df.setTimeZone(TimeZone.getTimeZone("Europe/London")); // ?
                tvDate.setText(df.format(date));

                // Distance
                TextView tvDist = (TextView) v.findViewById(R.id.iw_distance);
                tvDist.setText("Distance: " + info[1] + " m");

                // Altitude
                TextView tvAlt = (TextView) v.findViewById(R.id.iw_altitude);
                tvAlt.setText("Altitude: " + info[2] + " m");

                // Latitude et longitude
                TextView tvLoc = (TextView) v.findViewById(R.id.iw_location);
                tvLoc.setText("Lat: " + info[3] + ", long: " + info[4]);

                return v;
            }
        });

        // Gérer le clic long sur la carte
        map.setOnMapLongClickListener(MainActivity.this);


        if (canAccessFineLocation = ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            map.setMyLocationEnabled(true);
        }

        // Redessiner la trace KML
        if (configurationHasChanged && kmlTrack.b != null) {
            addPathToFragments(true, false);
            configurationHasChanged = false;
        }

        // Redessiner la trace d'enregistrement
        if (isConnectedToGeoLocService) {
            if (locBinder.isRecording()) {
                recordTrack.b = locBinder.getRecordData();
                addPathToFragments(false, true);
            }
        }
    }

    /**
     * Une sous-trace a été sélectionnée.
     * <p/>
     * <p>Trace et sous-trace sont systématiquement mises à jour sur la carte bien
     * que celle-ci ne soit pas affichée. La raison est qu'on ne détecte pas le
     * retour sur la carte lorsque l'utilisateur appuie sur le bouton arrière
     * (à améliorer ?).</p>
     *
     * @param geoIdx1 Indice de la géolocalisation de départ
     * @param geoIdx2 Indice de la géolocalisation de fin
     */
    @Override
    public void onGeoLocIntervalSeleted(int geoIdx1, int geoIdx2) {
        MapFragment gmf = (MapFragment) getFragmentManager().findFragmentByTag(
                FRAG_GMAP_TAG);
        if (map != null) { // La carte Google existe
            if (trackOnMapisKML) { // Trace KML
                kmlTrack.iSubTrackStart = geoIdx1;
                kmlTrack.iSubTrackEnd = geoIdx2;
                if (geoIdx1 == -1) { // Effacer la sous-trace
                    kmlTrack.iSubTrackStart = -1;
                    drawTrackOnMap(kmlTrack, getResources().getColor(R.color.kml_path),
                            map, kmlTrackStartImg, kmlTrackEndImg);
                } else if (geoIdx2 == -1) { // Dessiner uniquement le marqueur de départ
                    kmlTrack.iSubTrackEnd = kmlTrack.iSubTrackStart;
                    drawTrackOnMap(kmlTrack, getResources().getColor(R.color.kml_path),
                            map, kmlTrackStartImg, kmlTrackEndImg);

                } else { // Dessiner la sous-trace
                    drawTrackOnMap(kmlTrack, getResources().getColor(R.color.kml_path),
                            map, kmlTrackStartImg, kmlTrackEndImg);
                }
            } else { // Trace d'enregistrement
                recordTrack.iSubTrackStart = geoIdx1;
                recordTrack.iSubTrackEnd = geoIdx2;
                if (geoIdx1 == -1) { // Effacer la trace
                    recordTrack.iSubTrackStart = -1;
                    drawTrackOnMap(recordTrack,
                            getResources().getColor(R.color.record_path), map,
                            recTrackStartImg, recTrackEndImg);
                } else if (geoIdx2 == -1) { // Uniquement le marqueur de départ
                    recordTrack.iSubTrackEnd = recordTrack.iSubTrackStart;
                    drawTrackOnMap(recordTrack,
                            getResources().getColor(R.color.record_path), map,
                            recTrackStartImg, recTrackEndImg);
                } else { // Dessiner la sous-trace
                    drawTrackOnMap(recordTrack,
                            getResources().getColor(R.color.record_path), map,
                            recTrackStartImg, recTrackEndImg);
                }
            }
        }
    }

    /**
     * Méthode appelée lorsque la carte a été créée.
     *
     * @param googleMap
     */
    @Override
    public void onMapReady(GoogleMap googleMap) {
        map = googleMap;

        SharedPreferences settings = getPreferences(MODE_PRIVATE);
        float latitude = settings.getFloat(LATITUDE_PREF_KEY, 45.145f);
        float longitude = settings.getFloat(LONGITUDE_PREF_KEY, 5.72f);
        moveCamera2Loc(latitude, longitude);
    }
}
