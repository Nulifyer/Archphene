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
        try {
            TerminalRequestStore.Request validated = TerminalRequestStore.validate(
                    request.getStringExtra("archphene_terminal_request_id"),
                    request.getStringExtra("archphene_terminal_action"),
                    request.getStringExtra("archphene_terminal_query"));
            TerminalRequestStore.put(this, validated.id,
                    validated.action, validated.query);
            Intent manager = new Intent(this, MainActivity.class)
                    .putExtra("archphene_terminal_request_id", validated.id)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(manager);
        } catch (Exception error) {
            String requestId = request.getStringExtra("archphene_terminal_request_id");
            TerminalRequestStore.discard(this, requestId);
            if (requestId != null && requestId.matches("[a-zA-Z0-9._-]{1,64}")) {
                TerminalCommandReporter.report(this, requestId, "request", 0,
                        true, "error", "Manager rejected request");
            }
        }
        finish();
    }
}