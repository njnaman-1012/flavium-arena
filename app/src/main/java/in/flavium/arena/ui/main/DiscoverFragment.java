package in.flavium.arena.ui.main;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import in.flavium.arena.R;
import in.flavium.arena.ui.common.BaseActivity;
import in.flavium.arena.ui.common.BaseFragment;
import in.flavium.arena.ui.common.Ui;
import in.flavium.arena.ui.sports.CategoriesActivity;
import in.flavium.arena.model.Sport;

import java.util.List;

/** Discover tab: every sport as a picture tile. Tapping one opens its categories. */
public class DiscoverFragment extends BaseFragment {

    private View root;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        root = inflater.inflate(R.layout.fragment_discover, container, false);
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshScreen();
    }

    /** Shows the current data. Runs when the screen opens and when shared data changes. */
    @Override
    protected void refreshScreen() {
        LinearLayout tiles = root.findViewById(R.id.tileContainer);
        tiles.removeAllViews();

        List<Sport> sports = repo().getSports();
        for (int i = 0; i < sports.size(); i++) {
            Sport sport = sports.get(i);
            int categories = repo().getCategories(sport.id).size();
            View tile = Ui.addTile(tiles, i, Ui.sportPicture(sport.name), sport.name,
                    categories + (categories == 1 ? " category" : " categories"));
            tile.setOnClickListener(v -> {
                Intent intent = new Intent(requireContext(), CategoriesActivity.class);
                intent.putExtra(BaseActivity.EXTRA_SPORT_ID, sport.id);
                startActivity(intent);
            });
        }
        Ui.padLastRow(tiles, sports.size());
        root.findViewById(R.id.tvNoSports)
                .setVisibility(sports.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
