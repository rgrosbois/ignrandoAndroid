package fr.rg.ignrando.dialog;


import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.os.Bundle;

public class DeleteFileDialog extends DialogFragment {

    private static final String FILENAME_KEY = "filename";
    private static final String DIRECTORY_KEY = "directory";

    public interface Callbacks {
        public void deleteKMLFile(String nom);
    }
    private Callbacks dummyCallbacks = new Callbacks() {
        @Override
        public void deleteKMLFile(String nom) { }

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

    public static DeleteFileDialog newInstance(String fileName, String directory) {
        DeleteFileDialog dialog = new DeleteFileDialog();

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

        builder.setMessage("Supprimer "+fileName+" ?");
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                mCallbacks.deleteKMLFile(dir+fileName);
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