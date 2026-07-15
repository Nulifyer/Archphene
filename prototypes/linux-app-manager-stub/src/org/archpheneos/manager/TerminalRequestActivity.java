package org.archpheneos.manager;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Signature-protected entry point for Terminal package requests. */
public final class TerminalRequestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent request = getIntent();
        TerminalRequestStore.put(this,
                request.getStringExtra("archphene_terminal_action"),
                request.getStringExtra("archphene_terminal_query"));
        Intent manager = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(manager);
        finish();
    }
}