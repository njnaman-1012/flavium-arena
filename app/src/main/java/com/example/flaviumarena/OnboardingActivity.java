package com.example.flaviumarena;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.example.flaviumarena.auth.Session;

/** Three welcome pages shown on first launch. The public explores; organisers log in. */
public class OnboardingActivity extends BaseActivity {

    private static final int[] PICTURES = {
            R.drawable.ill_onboard_1, R.drawable.ill_onboard_2, R.drawable.ill_onboard_3};
    private static final String[] TITLES = {
            "Every Sport in One Place",
            "Live Scores as They Happen",
            "Points Tables and Results"};
    private static final String[] TEXTS = {
            "Browse all FLAVIUM sports and categories, see who has registered and find "
                    + "when and where each match is played.",
            "Organisers update scores from the ground, so you can follow every match "
                    + "without leaving your seat.",
            "Standings are worked out from finalised results, so the table you see is "
                    + "always the official one."};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);
        applyInsets();

        ViewPager2 pager = findViewById(R.id.onboardingPager);
        LinearLayout dots = findViewById(R.id.onboardingDots);
        pager.setAdapter(new PageAdapter());
        Ui.showDots(dots, TITLES.length, 0);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                Ui.showDots(dots, TITLES.length, position);
            }
        });

        // The public needs no account: "Explore" goes straight to the home screen.
        findViewById(R.id.btnExplore).setOnClickListener(v -> {
            Session.setOnboardingSeen(this, true);
            openHome();
        });
        findViewById(R.id.btnLogin).setOnClickListener(v -> leaveTo(LoginActivity.class));
    }

    /** Remembers that the welcome pages were seen, opens Home and then the chosen screen on top. */
    private void leaveTo(Class<?> screen) {
        Session.setOnboardingSeen(this, true);
        Intent home = new Intent(this, MainActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        // Starts both screens in order, so Back from the second one returns to Home.
        startActivities(new Intent[]{home, new Intent(this, screen)});
        finish();
    }

    /** One page per entry of the arrays above, using item_onboarding_page.xml. */
    private static class PageAdapter extends RecyclerView.Adapter<PageAdapter.Holder> {

        static class Holder extends RecyclerView.ViewHolder {
            Holder(View view) {
                super(view);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_onboarding_page, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            View v = holder.itemView;
            ((ImageView) v.findViewById(R.id.ivOnboarding)).setImageResource(PICTURES[position]);
            ((TextView) v.findViewById(R.id.tvOnboardingTitle)).setText(TITLES[position]);
            ((TextView) v.findViewById(R.id.tvOnboardingText)).setText(TEXTS[position]);
        }

        @Override
        public int getItemCount() {
            return TITLES.length;
        }
    }
}
