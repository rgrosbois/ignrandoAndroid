package fr.rg.ignrando;


import android.app.Activity;
import android.app.ListFragment;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;

public class SuggestFragment extends ListFragment {

    private MainActivity mainActivity;
    private static final String GEOLOC_ARRAY = "geoloc_array";

    @Override
    public void onAttach(Activity activity) {
        if( !(activity instanceof MainActivity) ) {
            throw new IllegalStateException(
                    "Activity must be of type MainActivity.");
        }
        this.mainActivity = (MainActivity)activity;
        super.onAttach(activity);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        this.mainActivity = null;
    }

    public static SuggestFragment newInstance(String[] args) {
        SuggestFragment sf = new SuggestFragment();
        Bundle b = new Bundle();
        b.putStringArray(GEOLOC_ARRAY, args);
        sf.setArguments(b);
        return sf;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setListAdapter(new ArrayAdapter<>(getActivity(), android.R.layout.simple_list_item_1,
                getArguments().getStringArray(GEOLOC_ARRAY)));
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        super.onListItemClick(l, v, position, id);
        if(mainActivity!=null) {
            mainActivity.onAddressSelected((String)l.getItemAtPosition(position));
        }
    }


}