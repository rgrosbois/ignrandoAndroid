package fr.rg.ignrando.dialog;


import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import fr.rg.ignrando.R;

public class RenameFileDialog extends DialogFragment {

    private static final String FILENAME_KEY = "filename";
    private static final String DIRECTORY_KEY = "directory";

    public interface Callbacks {
        public void renameKMLFile(String ancienNom, String nouveauNom);
    }
    private Callbacks dummyCallbacks = new Callbacks() {
        @Override
        public void renameKMLFile(String ancienNom, String nouveauNom) { }

    };
    private Callbacks mCallbacks = dummyCallbacks;
    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        if(!(activity instanceof Callbacks))
            throw new IllegalStateException("Activity must implement Callbacks interface");
        mCallbacks = (Callbacks)activity;
    }
    @Override
    public void onDetach() {
        super.onDetach();
        mCallbacks = dummyCallbacks;
    }

    public static RenameFileDialog newInstance(String fileName, String directory) {
        RenameFileDialog dialog = new RenameFileDialog();

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

        LayoutInflater inflater = getActivity().getLayoutInflater();
        View root = inflater.inflate(R.layout.renommer_fichier, null);
        builder.setView(root);
        final EditText nouveauNom = (EditText)root.findViewById(R.id.nouveau_nom);
        nouveauNom.setText(fileName);

        builder.setTitle("Renommer "+fileName);
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                mCallbacks.renameKMLFile(dir+fileName, dir+(nouveauNom.getText().toString()));
            }
        });
        builder.setNegativeButton("Annuler", new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) { }
        });
        builder.setCancelable(true);
        return builder.create();
    }

}