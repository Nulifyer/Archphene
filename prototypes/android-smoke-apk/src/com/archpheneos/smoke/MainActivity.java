package com.archpheneos.smoke;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView label = new TextView(this);
        label.setGravity(Gravity.CENTER);
        label.setText("ArchpheneOS VM smoke test\nAPK install and launch verified");
        label.setTextSize(22);
        label.setPadding(32, 32, 32, 32);
        setContentView(label);
    }
}
