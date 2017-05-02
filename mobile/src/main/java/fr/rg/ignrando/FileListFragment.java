package fr.rg.ignrando;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;

import android.Manifest;
import android.app.Activity;
import android.app.ListFragment;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import fr.rg.ignrando.dialog.KMLOptionsDialog;

/**
 * Un fragment de liste permettant de naviguer dans le système de fichier
 * et de sélectionner un fichier KML à analyser.
 * <p>
 * L'activité contenant ce fragment doit implémenter l'interface {@link Callbacks}.
 * <p>
 * This fragment also supports
 * tablet devices by allowing list items to be given an 'activated' state upon
 * selection. This helps indicate which item is currently being viewed in a
 * MapFragment.
 */
public class FileListFragment extends ListFragment {

    /**
     * Pour sauvegarder la dernière position sélectionnée.
     */
    private static final String STATE_ACTIVATED_POSITION = "activated_position";
    /**
     * Pour sauvegarder le dernier dossier courant.
     */
    private final static String CUR_DIR_KEY = "current_directory";

    /**
     * Liste des fichiers et répertoires dans le dossier courant.
     */
    private ArrayList<String> listeFichiers;
    /**
     * Pour gérer le contenu de l'affichage.
     */
    private ArrayAdapter<String> aa;
    /**
     * Dossier courant.
     */
    private File curDir;
    /**
     * Pour afficher le dossier courant
     */
    private TextView curDirTV;
    /**
     * Le dossier parent.
     */
    private final static String parentDirPath = ".." + File.separator;
    /**
     * Position sélectionnée.
     */
    private int mActivatedPosition = ListView.INVALID_POSITION;
    /**
     * Activité à notifier
     */
    private Callbacks mCallbacks = sDummyCallbacks;

    /**
     * Interface de rappel à implémenter par toute activité contenant ce
     * fragment et désirant être notifiée de la sélection d'un fichier.
     */
    public interface Callbacks {
        /**
         * Méthode de rappel.
         */
        public void onKMLFileSelected(String id);
    }

    /**
     * Implémentation bidon de l'interface {@link Callbacks} lorsque ce
     * fragment n'est attaché à aucune activité.
     */
    private static Callbacks sDummyCallbacks = new Callbacks() {
        @Override
        public void onKMLFileSelected(String id) {
        }
    };

    /**
     * Constructeur vide obligatoire pour une instantiation par le FragmentManager.
     */
    public FileListFragment() {
    }

    /**
     * Création du fragment.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Vérifier que l'utilisateur a le droit d'accéder au
        // périphérique de stockage externe
        ((MainActivity) getActivity()).canReadExtMem =
                ContextCompat.checkSelfPermission(getActivity(),
                        android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                        PackageManager.PERMISSION_GRANTED;
        if (!((MainActivity) getActivity()).canReadExtMem) {
            // Demander l'autorisation
            ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    MainActivity.MY_PERMISSION_READ_EXTERNAL_STORAGE);
        } else {
            // Dernier dossier courant
            curDir = new File(PreferenceManager.getDefaultSharedPreferences(getActivity()).
                    getString(CUR_DIR_KEY, Environment.getExternalStorageDirectory()
                            .getPath()));

            listeFichiers = new ArrayList<>();
            aa = new ArrayAdapter<>(getActivity(),
                    R.layout.fragment_fileitem,
                    R.id.kml_item, listeFichiers);

            setListAdapter(aa);
            if (curDir.isDirectory()) majListeFichiers();
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
    }

    /**
     * Dé-sérialiser le contenu de l'interface.
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_filelist, container, false);
        curDirTV = (TextView) rootView.findViewById(R.id.repertoire);
        updateCurDirDisplay();
        return rootView;
    }

    /**
     * Mettre à jour l'affichage du dossier courant.
     */
    private void updateCurDirDisplay() {
        if (((MainActivity) getActivity()).canReadExtMem) {
            curDirTV.setText(curDir.getAbsolutePath() + File.separator);
        } else {
            curDirTV.setText("Accès mémoire non autorisé");
        }
    }

    /**
     * Mettre à jour la liste des fichiers du dossier courant
     */
    public void majListeFichiers() {
        if (listeFichiers == null || !((MainActivity) getActivity()).canReadExtMem) return;

        listeFichiers.clear();
        for (File file : curDir.listFiles()) {
            if (file.isDirectory()) {
                listeFichiers.add(file.getName() + File.separator);
            } else {
                listeFichiers.add(file.getName());
            }
        }
        aa.sort(new Comparator<String>() { // Tri alphabétique des fichiers
            public int compare(String file1, String file2) {
                return file2.compareTo(file1);
            }
        });
        listeFichiers.add(0, parentDirPath);
        aa.notifyDataSetChanged();
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Resélectionner la dernière position
        if (savedInstanceState != null
                && savedInstanceState.containsKey(STATE_ACTIVATED_POSITION)) {
            setActivatedPosition(savedInstanceState.getInt(STATE_ACTIVATED_POSITION));
        }

        // Gestion d'un clic Long
        if (((MainActivity) getActivity()).canReadExtMem) {
            getListView().setOnItemLongClickListener(new OnItemLongClickListener() {

                @Override
                public boolean onItemLongClick(AdapterView<?> arg0, View tv,
                                               int arg2, long arg3) {
                    KMLOptionsDialog
                            .newInstance(((TextView) tv).getText().toString(),
                                    curDir.getAbsolutePath() + File.separator)
                            .show(getFragmentManager(), "Renommer fichier");
                    return true;
                }
            });
        }

    }

    /**
     * Récupérer la référence de l'activité appelante.
     */
    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        if (!(activity instanceof Callbacks)) {
            throw new IllegalStateException(
                    "Activity must implement fragment's callbacks.");
        }
        mCallbacks = (Callbacks) activity;
    }

    /**
     * Se dissocier de l'activité appelante
     */
    @Override
    public void onDetach() {
        super.onDetach();
        mCallbacks = sDummyCallbacks;
    }

    /**
     * Gestion du clic sur la liste
     */
    @Override
    public void onListItemClick(ListView listView, View view, int position,
                                long id) {
        super.onListItemClick(listView, view, position, id);

        String fichier = ((TextView) view).getText().toString();

        // Simplification du chemin lors d'une remontée dans l'arborescence
        if (fichier.equalsIgnoreCase(parentDirPath)) {
            if (curDir.getParentFile() != null) curDir = curDir.getParentFile();
        } else {
            curDir = new File(curDir.getAbsolutePath() + File.separator + fichier);
        }

        if (curDir.isDirectory()) { // Sélection d'un répertoire
            updateCurDirDisplay();
            majListeFichiers();
        } else { // Sélection d'un fichier
            mCallbacks.onKMLFileSelected(curDir.getAbsolutePath());

            // Revenir au répertoire parent pour la prochaine fois
            curDir = curDir.getParentFile();
            SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getActivity());
            SharedPreferences.Editor editor = settings.edit();
            editor.putString(CUR_DIR_KEY, curDir.getAbsolutePath());
            editor.apply();
        }
    }


    /**
     * Sauvegarder de la sélection courante.
     */
    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mActivatedPosition != ListView.INVALID_POSITION) {
            // Serialize and persist the activated item position.
            outState.putInt(STATE_ACTIVATED_POSITION, mActivatedPosition);
        }
    }

    /**
     * Turns on activate-on-click mode. When this mode is on, list items will be
     * given the 'activated' state when touched.
     */
    public void setActivateOnItemClick(boolean activateOnItemClick) {
        // When setting CHOICE_MODE_SINGLE, ListView will automatically
        // give items the 'activated' state when touched.
        getListView().setChoiceMode(
                activateOnItemClick ? ListView.CHOICE_MODE_SINGLE
                        : ListView.CHOICE_MODE_NONE);
    }

    /**
     * Sélectionner la position
     */
    private void setActivatedPosition(int position) {
        if (position == ListView.INVALID_POSITION) {
            getListView().setItemChecked(mActivatedPosition, false);
        } else {
            getListView().setItemChecked(position, true);
        }

        mActivatedPosition = position;
    }

}