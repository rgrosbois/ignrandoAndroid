package fr.rg.ignrando.dialog;


import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.os.Bundle;

import fr.rg.ignrando.MainActivity;
import fr.rg.ignrando.R;

public class RecordDialog extends DialogFragment {

    private static final String ENCOURS_KEY = "en cours";

    private MainActivity mainActivity;
    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mainActivity = (MainActivity)activity;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mainActivity = null;
    }

    public static RecordDialog newInstance(boolean enCours) {
        RecordDialog dialog = new RecordDialog();

        Bundle args = new Bundle();
        args.putBoolean(ENCOURS_KEY, enCours);
        dialog.setArguments(args);

        return dialog;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        boolean enCours = getArguments().getBoolean(ENCOURS_KEY);

        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());

        if(enCours) { // Enregistrement en cours
            builder.setTitle(getString(R.string.record_in_progress));
            builder.setItems(R.array.trace_en_cours_carte, new OnClickListener() {

                @Override
                public void onClick(DialogInterface dialog, int which) {
                    switch(which) {
                        case 0: // Annuler
                            mainActivity.cancelRecord();
                            break;
                        case 1: // Arrêter
                            mainActivity.stopRecord();
                            break;
                    }
                }
            });
        } else { // Débute l'enregistrement
            builder.setTitle(getString(R.string.new_record));
            builder.setMessage("Attention: l'enregistrement ne " +
                    "débutera réellement que lorsque le GPS " +
                    "aura trouvé une position valide.");
            builder.setPositiveButton("Démarrer", new OnClickListener() {

                @Override
                public void onClick(DialogInterface dialog, int which) {
                    mainActivity.startRecord();
                }
            });
        }
        builder.setCancelable(true);
        return builder.create();
    }

}