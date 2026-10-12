package com.example.flaviumarena.ui.common;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.flaviumarena.R;
import com.example.flaviumarena.util.ImageUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * One adapter for every list in the app. Each row is a card with something round on the
 * left, a title, a subtitle and a small badge. See row_item.xml.
 *
 * The round part shows, in this order of preference: a saved logo (imagePath), a
 * built-in picture, or a short text (circleText, otherwise the first letter of the title).
 */
public class RowAdapter extends BaseAdapter {

    public static class Row {
        public final String id;
        public final String title;
        public final String subtitle;
        public final String badge;
        /** Built-in drawable shown on the left, or 0 for none. */
        public final int picture;
        /** Path of a saved team logo, or null. */
        public String imagePath;
        /** Text for the circle (for example a rank), or null to use the title's first letter. */
        public String circleText;

        public Row(String id, String title, String subtitle, String badge) {
            this(id, title, subtitle, badge, 0);
        }

        public Row(String id, String title, String subtitle, String badge, int picture) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.badge = badge;
            this.picture = picture;
        }
    }

    private final LayoutInflater inflater;
    private final List<Row> rows = new ArrayList<>();

    public RowAdapter(Context context) {
        inflater = LayoutInflater.from(context);
    }

    public void setRows(List<Row> newRows) {
        rows.clear();
        rows.addAll(newRows);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public Row getItem(int position) {
        return rows.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.row_item, parent, false);
        }
        bind(convertView, rows.get(position));
        return convertView;
    }

    /** Puts the values of a row into a row_item view. Also used outside lists. */
    public static void bind(View view, Row row) {
        TextView initial = view.findViewById(R.id.tvRowInitial);
        ImageView icon = view.findViewById(R.id.ivRowIcon);
        TextView title = view.findViewById(R.id.tvRowTitle);
        TextView subtitle = view.findViewById(R.id.tvRowSubtitle);
        TextView badge = view.findViewById(R.id.tvRowBadge);

        Bitmap logo = ImageUtil.load(row.imagePath);
        if (logo != null) {
            showLogo(icon, logo);
            initial.setVisibility(View.GONE);
        } else if (row.picture != 0) {
            icon.setImageResource(row.picture);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setBackground(null);
            icon.setClipToOutline(false);
            icon.setVisibility(View.VISIBLE);
            initial.setVisibility(View.GONE);
        } else {
            String text = row.circleText != null ? row.circleText : Ui.initial(row.title);
            initial.setText(text);
            // Longer texts such as "EXTC" need smaller letters to fit in the circle.
            initial.setTextSize(text.length() > 2 ? 11 : 16);
            initial.setVisibility(View.VISIBLE);
            icon.setVisibility(View.GONE);
        }
        title.setText(row.title);
        subtitle.setText(row.subtitle);
        badge.setText(row.badge);
        subtitle.setVisibility(isEmpty(row.subtitle) ? View.GONE : View.VISIBLE);
        badge.setVisibility(isEmpty(row.badge) ? View.GONE : View.VISIBLE);
    }

    /** Shows a photo cropped to a circle. */
    public static void showLogo(ImageView view, Bitmap logo) {
        view.setImageBitmap(logo);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setBackgroundResource(R.drawable.bg_circle_soft);
        view.setClipToOutline(true);
        view.setVisibility(View.VISIBLE);
    }

    private static boolean isEmpty(String text) {
        return text == null || text.isEmpty();
    }
}
