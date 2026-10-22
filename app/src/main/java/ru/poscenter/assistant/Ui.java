package ru.poscenter.assistant;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG = Color.rgb(16, 25, 28), SURFACE = Color.rgb(27, 40, 44), TEXT = Color.rgb(237, 245, 243), MUTED = Color.rgb(166, 187, 184), ACCENT = Color.rgb(117, 205, 188);
    static int dp(Context c, float n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }
    static GradientDrawable background(int color, int border, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(radius);
        drawable.setStroke(1, border); return drawable;
    }
    static LinearLayout column(Context c) {
        LinearLayout layout = new LinearLayout(c); layout.setOrientation(LinearLayout.VERTICAL); return layout;
    }
    static TextView text(Context c, String value, int size, boolean bold) {
        TextView view = new TextView(c); view.setText(value); view.setTextColor(TEXT); view.setTextSize(size);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        view.setPadding(0, dp(c, 8), 0, dp(c, 8)); return view;
    }
    static Button button(Context c, String title, Runnable action) {
        Button button = new Button(c); button.setText(title); button.setAllCaps(false); button.setTextSize(15); button.setTextColor(TEXT);
        button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(dp(c, 44));
        button.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6));
        button.setBackground(background(SURFACE, Color.rgb(58, 80, 81), dp(c, 5)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(c, 4), 0, dp(c, 4));
        button.setLayoutParams(params); button.setOnClickListener(v -> action.run()); return button;
    }
    static EditText field(Context c, String hint, String value, boolean multiline) {
        EditText field = new EditText(c); field.setHint(hint); field.setText(value); field.setTextColor(TEXT); field.setHintTextColor(MUTED); field.setTextSize(16);
        field.setSingleLine(!multiline);
        if (multiline) { field.setMinLines(3); field.setGravity(android.view.Gravity.TOP); }
        field.setBackground(background(SURFACE, Color.rgb(58, 80, 81), dp(c, 4)));
        field.setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10));
        field.setLayoutParams(new LinearLayout.LayoutParams(-1, -2)); return field;
    }
    static LinearLayout row(Context c, View... children) {
        LinearLayout row = new LinearLayout(c); row.setOrientation(LinearLayout.HORIZONTAL);
        for (View child : children) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.setMargins(dp(c, 2), dp(c, 3), dp(c, 2), dp(c, 3));
            row.addView(child, params);
        }
        return row;
    }
}
