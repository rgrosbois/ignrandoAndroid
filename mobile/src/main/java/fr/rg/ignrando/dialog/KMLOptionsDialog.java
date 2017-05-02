package fr.rg.ignrando.dialog;


import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.os.Bundle;

import fr.rg.ignrando.R;

public class KMLOptionsDialog extends DialogFragment {

    private static final String FILENAME_KEY = "filename";
    private static final String DIRECTORY_KEY = "directory";

    public static KMLOptionsDialog newInstance(String fileName, String directory) {
        KMLOptionsDialog dialog = new KMLOptionsDialog();

        Bundle args = new Bundle();
        args.putString(FILENAME_KEY, fileName);
        args.putString(DIRECTORY_KEY, directory);
        dialog.setArguments(args);

        return dialog;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final String fileName = getArguments().getString(FILENAME_KEY);
        final String dir = getArguments().getString(DIRECTORY_KEY);

        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());

        builder.setTitle(fileName);
        builder.setItems(R.array.kml_options, new OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                switch(which) {
                    case 0: // Renommer
                        RenameFileDialog
                                .newInstance(fileName,dir)
                                .show(getFragmentManager(), "Renommer fichier");
                        break;
                    case 1: // Supprimer
                        DeleteFileDialog
                                .newInstance(fileName,dir)
                                .show(getFragmentManager(), "Supprimer fichier");
                        break;
                }
            }
        });
        builder.setCancelable(true);
        return builder.create();
    }

}